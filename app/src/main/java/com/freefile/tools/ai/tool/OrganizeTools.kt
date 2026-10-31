package com.freefile.tools.ai.tool

import android.content.ClipData
import android.content.Intent
import android.provider.MediaStore
import com.freefile.tools.ai.model.ErrorCode
import com.freefile.tools.ai.model.ToolPermission
import com.freefile.tools.ai.model.ToolResult
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.joinPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.freefile.tools.ai.tool.ToolArgs.int
import com.freefile.tools.ai.tool.ToolArgs.long
import com.freefile.tools.ai.tool.ToolArgs.str
import com.freefile.tools.ai.tool.ToolArgs.strList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 组织类工具：批量重命名、标签、分享、媒体库查询。
 */

// ----------------------------------------------------------- 标签存储

/**
 * 标签仓库：path → tags，持久化在 filesDir/agent_tags.json。
 * 仅 Agent 内部使用，格式简单、原子写入（临时文件 + rename）。
 */
object TagStore {

    private val mutex = Mutex()
    private var loaded = false
    private val map = HashMap<String, MutableSet<String>>()

    private fun file(ctx: android.content.Context): File = File(ctx.filesDir, "agent_tags.json")

    private fun load(ctx: android.content.Context) {
        if (loaded) return
        val f = file(ctx)
        if (f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                o.keys().forEach { path ->
                    val arr = o.optJSONArray(path) ?: return@forEach
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let(set::add)
                    if (set.isNotEmpty()) map[path] = set
                }
            }
        }
        loaded = true
    }

    private fun persist(ctx: android.content.Context) {
        val o = JSONObject()
        map.forEach { (path, tags) -> if (tags.isNotEmpty()) o.put(path, JSONArray(tags.toList())) }
        val target = file(ctx)
        val tmp = File(ctx.filesDir, "agent_tags.json.tmp")
        runCatching {
            tmp.writeText(o.toString())
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }

    suspend fun tagsOf(ctx: android.content.Context, path: String): Set<String> = mutex.withLock {
        load(ctx)
        map[path]?.toSet() ?: emptySet()
    }

    suspend fun update(
        ctx: android.content.Context,
        paths: List<String>,
        add: List<String>,
        remove: List<String>,
        replaceWith: List<String>?
    ): Int = mutex.withLock {
        load(ctx)
        var changed = 0
        paths.forEach { path ->
            val cur = map.getOrPut(path) { HashSet() }
            if (replaceWith != null) {
                cur.clear()
                cur.addAll(replaceWith)
                changed++
            } else {
                var touched = false
                add.forEach { if (cur.add(it)) touched = true }
                remove.forEach { if (cur.remove(it)) touched = true }
                if (touched) changed++
            }
            if (cur.isEmpty()) map.remove(path)
        }
        persist(ctx)
        changed
    }

    suspend fun clearTag(ctx: android.content.Context, tag: String): Int = mutex.withLock {
        load(ctx)
        var n = 0
        map.entries.forEach { (_, set) -> if (set.remove(tag)) n++ }
        map.entries.removeAll { it.value.isEmpty() }
        persist(ctx)
        n
    }

    suspend fun all(ctx: android.content.Context): Map<String, List<String>> = mutex.withLock {
        load(ctx)
        map.mapValues { it.value.toList() }
    }
}

class TagTool : AgentTool {
    override val name = "tag"
    override val description =
        "管理文件标签：action=add/remove/set 给指定路径打标签，list 查询路径的标签或全部标签，clear 删除某个标签。" +
            "标签只保存在本机 Agent 数据库，不会修改文件。"
    override val permission = ToolPermission.WRITE
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("add", "remove", "set", "list", "clear"))))
                .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("tags", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        )
        .put("required", JSONArray(listOf("action")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val action = args.str("action").lowercase()
            val paths = args.strList("paths")
            val tags = args.strList("tags").map { it.take(24) }.filter { it.isNotEmpty() }
            when (action) {
                "add", "remove", "set" -> {
                    if (paths.isEmpty() || tags.isEmpty()) {
                        return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "需要 paths 与 tags")
                    }
                    val changed = TagStore.update(
                        ctx.appContext, paths,
                        add = if (action == "remove") emptyList() else tags,
                        remove = if (action == "remove") tags else emptyList(),
                        replaceWith = if (action == "set") tags else null
                    )
                    ToolResult.ok(
                        JSONObject()
                            .put("changed", changed)
                            .put("paths", JSONArray(paths))
                            .put("tags", JSONArray(tags)),
                        "已更新 $changed 个文件的标签"
                    )
                }
                "list" -> {
                    if (paths.isEmpty()) {
                        val all = TagStore.all(ctx.appContext)
                        val counts = HashMap<String, Int>()
                        all.values.flatten().forEach { counts[it] = (counts[it] ?: 0) + 1 }
                        val arr = JSONArray()
                        counts.entries.sortedByDescending { it.value }.forEach { (t, c) ->
                            arr.put(JSONObject().put("tag", t).put("file_count", c))
                        }
                        ToolResult.ok(JSONObject().put("tags", arr), "共 ${counts.size} 个标签")
                    } else {
                        val arr = JSONArray()
                        paths.forEach { p ->
                            arr.put(
                                JSONObject()
                                    .put("path", p)
                                    .put("tags", JSONArray(TagStore.tagsOf(ctx.appContext, p).toList()))
                            )
                        }
                        ToolResult.ok(JSONObject().put("items", arr))
                    }
                }
                "clear" -> {
                    if (tags.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "需要 tags")
                    var n = 0
                    tags.forEach { n += TagStore.clearTag(ctx.appContext, it) }
                    ToolResult.ok(JSONObject().put("removed", n), "已移除 $n 处标签")
                }
                else -> ToolResult.error(ErrorCode.INVALID_ARGS, "未知 action：$action")
            }
        }
}

// -------------------------------------------------------------- 批量重命名

class BatchRenameTool : AgentTool {
    override val name = "batch_rename"
    override val description =
        "按规则批量重命名文件。mode=regex 用 find/replace 正则替换名称（支持 \\1 捕获组）；mode=template 用模板，" +
            "支持占位符 {name} 原名、{ext} 扩展名、{index} 序号、{date} 当天日期。会先展示预览并等待确认，可撤销。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = true

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("regex", "template"))))
                .put("find", JSONObject().put("type", "string").put("description", "regex 模式的正则"))
                .put("replace", JSONObject().put("type", "string").put("description", "regex 模式的替换文本"))
                .put("template", JSONObject().put("type", "string").put("description", "template 模式，如 {name}_{index}"))
                .put("start_index", JSONObject().put("type", "integer").put("description", "序号起始，默认 1"))
        )
        .put("required", JSONArray(listOf("paths", "mode")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val paths = args.strList("paths").distinct()
            if (paths.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 paths")
            val mode = args.str("mode", "template").lowercase()
            val planned = ArrayList<Triple<String, String, String>>() // oldPath, newName, newPath
            val errors = ArrayList<JSONObject>()
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val startIndex = args.int("start_index", 1)

            paths.forEachIndexed { i, raw ->
                val check = PathGuard.checkWrite(ctx.appContext, raw)
                if (!check.allowed) {
                    errors.add(JSONObject().put("path", raw).put("reason", check.reason ?: "不可写"))
                    return@forEachIndexed
                }
                val path = check.normalized
                val name = path.substringAfterLast('/')
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot + 1) else ""
                val newBase: String? = if (mode == "regex") {
                    val find = args.str("find")
                    if (find.isEmpty()) {
                        errors.add(JSONObject().put("path", raw).put("reason", "regex 模式缺少 find"))
                        return@forEachIndexed
                    }
                    try {
                        Regex(find).replace(base, args.str("replace"))
                    } catch (t: Throwable) {
                        errors.add(JSONObject().put("path", raw).put("reason", "正则无效：${t.message}"))
                        return@forEachIndexed
                    }
                } else {
                    val tpl = args.str("template")
                    if (tpl.isEmpty()) {
                        errors.add(JSONObject().put("path", raw).put("reason", "template 模式缺少 template"))
                        return@forEachIndexed
                    }
                    tpl.replace("{name}", base)
                        .replace("{ext}", ext)
                        .replace("{index}", (startIndex + i).toString())
                        .replace("{date}", today)
                }
                if (newBase.isNullOrBlank() || newBase.contains('/')) {
                    errors.add(JSONObject().put("path", raw).put("reason", "生成的文件名非法"))
                    return@forEachIndexed
                }
                val newName = if (ext.isEmpty()) newBase else "$newBase.$ext"
                planned.add(Triple(path, newName, joinPath(dirOf(path), newName)))
            }

            if (planned.isEmpty()) {
                return@withContext ToolResult.error(
                    ErrorCode.INVALID_ARGS,
                    "没有可重命名的条目",
                    JSONObject().put("errors", JSONArray(errors))
                )
            }

            val entries = planned.map { (old, _, _) ->
                val engine = EngineSelector.select(old)
                engine.stat(old) ?: com.freefile.vfs.VfsEntry(
                    path = old, name = old.substringAfterLast('/'), isDirectory = false
                )
            }
            val preview = WriteSupport.preview(
                toolName = name,
                title = "批量重命名（${planned.size} 项）",
                entries = entries,
                destination = "",
                destinationEditable = false,
                destinationLabel = "预览：旧名 → 新名",
                conflicts = emptyList()
            ).copy(extras = planned.associate { (old, newName, _) -> old to newName })

            val approval = ctx.gate.request(preview) ?: return@withContext ToolResult.cancelledByUser()
            val chosen = planned.filter { approval.selectedPaths.contains(it.first) }
            if (chosen.isEmpty()) return@withContext ToolResult.cancelledByUser()

            val ok = ArrayList<String>()
            val failed = ArrayList<Pair<String, String>>()
            val inverse = ArrayList<Pair<String, String>>() // newPath → oldPath
            chosen.forEach { (oldPath, _, newPath) ->
                try {
                    val engine = EngineSelector.select(oldPath)
                    if (engine.exists(newPath)) {
                        failed.add(oldPath to "目标已存在：$newPath")
                    } else if (engine.rename(oldPath, newPath)) {
                        ok.add(newPath)
                        inverse.add(newPath to oldPath)
                    } else {
                        failed.add(oldPath to "重命名失败")
                    }
                } catch (t: Throwable) {
                    failed.add(oldPath to (t.message ?: "IO 错误"))
                }
            }
            WriteSupport.notifyDirs(*chosen.map { dirOf(it.first) }.distinct().toTypedArray())
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "批量重命名 ${chosen.size} 项",
                "成功 ${ok.size}，失败 ${failed.size}",
                ok.size, failed.size, 0,
                true, ctx.undoManager.inverseRenamePairs(inverse), dirOf(chosen.first().first)
            )
            val failArr = JSONArray()
            failed.forEach { (p, r) -> failArr.put(JSONObject().put("path", p).put("reason", r)) }
            ToolResult.ok(
                JSONObject()
                    .put("success", ok.size)
                    .put("failed", failed.size + errors.size)
                    .put("paths", JSONArray(ok))
                    .put("failures", failArr)
                    .put("errors", errors),
                "已重命名 ${ok.size} 项"
            )
        }
}

// ------------------------------------------------------------------ 分享

class ShareFilesTool : AgentTool {
    override val name = "share_files"
    override val description =
        "把文件发送到系统分享面板（ACTION_SEND_MULTIPLE）。属于外发动作，会先展示确认卡并等待用户确认。"
    override val permission = ToolPermission.EXTERNAL
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("text", JSONObject().put("type", "string").put("description", "附加说明文字，可空"))
        )
        .put("required", JSONArray(listOf("paths")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val prepared = WriteSupport.prepareEntries(ctx, args.strList("paths"))
            if (prepared.entries.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "文件不存在：${prepared.missing.joinToString(";")}")
            }
            val preview = WriteSupport.preview(
                toolName = name,
                title = "分享文件到其他应用",
                entries = prepared.entries,
                destination = "",
                destinationEditable = false,
                destinationLabel = "系统分享面板（文件将发送给所选应用）",
                conflicts = emptyList(),
                undoable = false
            )
            val approval = ctx.gate.request(preview) ?: return@withContext ToolResult.cancelledByUser()
            val chosen = prepared.entries.filter { approval.selectedPaths.contains(it.path) }
            if (chosen.isEmpty()) return@withContext ToolResult.cancelledByUser()

            val uris = chosen.mapNotNull { ShareSupport.uriOf(ctx.appContext, it.path) }
            if (uris.isEmpty()) {
                return@withContext ToolResult.error(
                    ErrorCode.IO_ERROR,
                    "无法生成文件 URI（可能不在 FileProvider 授权路径内）"
                )
            }
            val text = args.str("text")
            try {
                val intent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                }
                if (text.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, text)
                intent.clipData = ClipData.newRawUri("files", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.appContext.startActivity(
                    Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ctx.undoManager.record(
                    ctx.agentRepository, ctx.userInstruction, name,
                    "分享：${chosen.joinToString(";") { it.path }}",
                    "已打开分享面板", chosen.size, 0, 0, false, "", ""
                )
                ToolResult.ok(
                    JSONObject().put("shared", uris.size),
                    "已打开系统分享面板"
                )
            } catch (t: Throwable) {
                ToolResult.error(ErrorCode.PLUGIN_FAILED, "无法打开分享面板：${t.message}")
            }
        }
}

// -------------------------------------------------------------- 媒体库查询

class QueryMediaStoreTool : AgentTool {
    override val name = "query_media_store"
    override val description =
        "查询系统媒体库中的图片/视频/音频/文档（比遍历目录更快，符合 Scoped Storage）。支持按时间范围与名称关键词过滤。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("media_type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("image", "video", "audio", "file"))))
                .put("time_from", JSONObject().put("type", "integer").put("description", "修改时间不早于（epoch 毫秒）"))
                .put("time_to", JSONObject().put("type", "integer"))
                .put("keyword", JSONObject().put("type", "string").put("description", "文件名关键词"))
                .put("limit", JSONObject().put("type", "integer").put("description", "默认 100，最大 100"))
        )

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val mediaType = args.str("media_type", "image").lowercase()
            val typeValue = when (mediaType) {
                "image" -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                "video" -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                "audio" -> MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
                "file" -> MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
                else -> return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "未知 media_type")
            }
            val limit = args.int("limit", 100).coerceIn(1, 100)
            val from = args.long("time_from")
            val to = args.long("time_to")
            val keyword = args.str("keyword")

            val selection = StringBuilder("${MediaStore.Files.FileColumns.MEDIA_TYPE}=?")
            val selectArgs = arrayListOf(typeValue.toString())
            if (from > 0) {
                selection.append(" AND ${MediaStore.MediaColumns.DATE_MODIFIED}>=?")
                selectArgs.add((from / 1000).toString())
            }
            if (to > 0) {
                selection.append(" AND ${MediaStore.MediaColumns.DATE_MODIFIED}<=?")
                selectArgs.add((to / 1000).toString())
            }
            if (keyword.isNotEmpty()) {
                selection.append(" AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
                selectArgs.add("%$keyword%")
            }

            val projection = arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE
            )
            val items = ArrayList<JSONObject>()
            try {
                ctx.appContext.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection,
                    selection.toString(),
                    selectArgs.toTypedArray(),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT $limit"
                )?.use { c ->
                    val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    while (c.moveToNext() && items.size < limit) {
                        items.add(
                            JSONObject()
                                .put("path", c.getString(iData) ?: "")
                                .put("name", c.getString(iName) ?: "")
                                .put("size", c.getLong(iSize))
                                .put("last_modified", c.getLong(iDate) * 1000)
                                .put("mime", c.getString(iMime) ?: "")
                        )
                    }
                }
            } catch (t: Throwable) {
                return@withContext ToolResult.error(
                    ErrorCode.PERMISSION_DENIED,
                    "查询媒体库失败（可能缺少存储权限）：${t.message}"
                )
            }
            ToolResult.ok(
                ToolResults.baseList(items, items.size) { put("media_type", mediaType) },
                if (items.isEmpty()) "媒体库中没有符合条件的条目" else "找到 ${items.size} 个条目"
            )
        }
}
