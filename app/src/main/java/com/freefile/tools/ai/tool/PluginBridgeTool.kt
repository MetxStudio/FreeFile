package com.freefile.tools.ai.tool

import android.content.Intent
import androidx.core.content.FileProvider
import com.freefile.tools.ai.data.AgentSettings
import com.freefile.tools.ai.model.ErrorCode
import com.freefile.tools.ai.model.PreviewItem
import com.freefile.tools.ai.model.ToolPermission
import com.freefile.tools.ai.model.ToolResult
import com.freefile.tools.ai.model.WritePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.freefile.tools.ai.tool.ToolArgs.str
import com.freefile.tools.ai.tool.ToolArgs.strList
import java.io.File
import java.net.URLConnection

/**
 * 插件桥接（MVP）：把文件交给外部已安装 App 处理。
 * 插件目录来自 assets/apk/index.json（与工具托盘同一份）；不做 DexClassLoader 加载。
 *
 * action：
 *  - launch：仅启动插件（不带文件，无需确认）
 *  - view/open：ACTION_VIEW 打开单个文件（需要确认：文件会离开本应用）
 *  - send/share：ACTION_SEND / ACTION_SEND_MULTIPLE 发送文件（需要确认）
 */
class RunPluginActionTool : AgentTool {

    override val name = "run_plugin_action"
    override val description =
        "调用外部工具 App 处理文件。先用 list_plugins 查询可用 plugin_id；action 支持 launch（仅启动）、" +
            "view（打开文件）、send（发送文件）。文件会离开本应用交给外部 App，view/send 必须用户确认。"
    override val permission = ToolPermission.EXTERNAL
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("plugin_id", JSONObject().put("type", "string").put("description", "list_plugins 返回的 id，或直接传 Android 包名"))
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("launch", "view", "open", "send", "share"))))
                .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("mime", JSONObject().put("type", "string"))
        )
        .put("required", JSONArray(listOf("plugin_id")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val pluginId = args.str("plugin_id")
            if (pluginId.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 plugin_id")
            val action = args.str("action", "launch").lowercase()
            val rawPaths = args.strList("paths").distinct()
            val mimeOverride = args.str("mime")

            val catalog = PluginCatalog.load(ctx)
            val entry = PluginCatalog.resolve(catalog, pluginId)
                ?: return@withContext ToolResult.error(
                    ErrorCode.PLUGIN_NOT_FOUND,
                    "未知插件：$pluginId。请先用 list_plugins 查看可用插件。"
                )
            val pm = ctx.appContext.packageManager
            val installed = try {
                pm.getLaunchIntentForPackage(entry.packageName) != null
            } catch (_: Throwable) {
                false
            }
            if (!installed) {
                return@withContext ToolResult.error(
                    ErrorCode.NOT_INSTALLED,
                    "未安装插件「${entry.name}」（包名 ${entry.packageName}）。可在主页侧拉栏 → 工具托盘中安装。"
                )
            }

            when (action) {
                "launch" -> {
                    val intent = pm.getLaunchIntentForPackage(entry.packageName)
                        ?: return@withContext ToolResult.error(ErrorCode.PLUGIN_FAILED, "插件无法启动")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.appContext.startActivity(intent)
                    ToolResult.ok(
                        JSONObject().put("plugin_id", entry.id).put("package", entry.packageName),
                        "已启动 ${entry.name}"
                    )
                }

                "view", "open" -> {
                    if (rawPaths.size != 1) {
                        return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "view 只接受 1 个文件")
                    }
                    val file = localFile(ctx, rawPaths.first())
                        ?: return@withContext ToolResult.error(
                            ErrorCode.INVALID_ARGS,
                            "只能把本地真实文件交给外部插件（不支持远程文件/压缩包内路径）"
                        )
                    val preview = externalPreview(entry, "用 ${entry.name} 打开", listOf(file))
                    val approval = ctx.gate.request(preview)
                        ?: return@withContext ToolResult.cancelledByUser()
                    if (approval.selectedPaths.isEmpty()) return@withContext ToolResult.cancelledByUser()

                    val mime = mimeOverride.ifEmpty {
                        URLConnection.guessContentTypeFromName(file.name) ?: "*/*"
                    }
                    val uri = FileProvider.getUriForFile(
                        ctx.appContext,
                        "${ctx.appContext.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        .setPackage(entry.packageName)
                    if (pm.queryIntentActivities(intent, 0).isEmpty()) {
                        return@withContext ToolResult.error(
                            ErrorCode.PLUGIN_FAILED,
                            "${entry.name} 未声明能打开 $mime 类型文件"
                        )
                    }
                    ctx.appContext.startActivity(intent)
                    ToolResult.ok(
                        JSONObject().put("plugin_id", entry.id).put("path", file.absolutePath).put("mime", mime),
                        "已发送给 ${entry.name} 打开"
                    )
                }

                "send", "share" -> {
                    if (rawPaths.isEmpty()) {
                        return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "send 至少需要 1 个文件")
                    }
                    val files = rawPaths.mapNotNull { localFile(ctx, it) }
                    if (files.size != rawPaths.size) {
                        return@withContext ToolResult.error(
                            ErrorCode.INVALID_ARGS,
                            "只能发送本地真实文件（不支持远程文件/压缩包内路径）"
                        )
                    }
                    val preview = externalPreview(entry, "发送到 ${entry.name}", files)
                    val approval = ctx.gate.request(preview)
                        ?: return@withContext ToolResult.cancelledByUser()
                    val chosenFiles = files.filter { f -> approval.selectedPaths.contains(f.absolutePath) }
                    if (chosenFiles.isEmpty()) return@withContext ToolResult.cancelledByUser()

                    val mime = mimeOverride.ifEmpty {
                        if (chosenFiles.size == 1) {
                            URLConnection.guessContentTypeFromName(chosenFiles.first().name) ?: "*/*"
                        } else "*/*"
                    }
                    val uris = ArrayList<android.net.Uri>()
                    chosenFiles.forEach { f ->
                        uris.add(
                            FileProvider.getUriForFile(
                                ctx.appContext,
                                "${ctx.appContext.packageName}.fileprovider",
                                f
                            )
                        )
                    }
                    val intent = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND)
                            .setType(mime)
                            .putExtra(Intent.EXTRA_STREAM, uris.first())
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE)
                            .setType(mime)
                            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    }
                    intent
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        .setPackage(entry.packageName)
                    if (pm.queryIntentActivities(intent, 0).isEmpty()) {
                        return@withContext ToolResult.error(
                            ErrorCode.PLUGIN_FAILED,
                            "${entry.name} 未声明能接收这些文件"
                        )
                    }
                    ctx.appContext.startActivity(intent)
                    ToolResult.ok(
                        JSONObject()
                            .put("plugin_id", entry.id)
                            .put("count", chosenFiles.size)
                            .put("paths", JSONArray(chosenFiles.map { it.absolutePath })),
                        "已发送 ${chosenFiles.size} 个文件给 ${entry.name}"
                    )
                }

                else -> ToolResult.error(ErrorCode.INVALID_ARGS, "不支持的 action：$action")
            }
        }

    private suspend fun localFile(ctx: ToolContext, raw: String): File? {
        if (raw.startsWith("remote://") || raw.contains('#')) return null
        val check = PathGuard.checkRead(ctx.appContext, raw)
        if (!check.allowed) return null
        val f = File(check.normalized)
        if (!f.exists() || f.isDirectory) return null
        return f
    }

    private suspend fun externalPreview(
        entry: PluginCatalog.Entry,
        title: String,
        files: List<File>
    ): WritePreview {
        val items = files.map {
            PreviewItem(it.absolutePath, it.name, it.length(), false, true)
        }
        return WritePreview(
            token = java.util.UUID.randomUUID().toString(),
            toolName = name,
            title = title,
            items = items,
            destination = "${entry.name}（${entry.packageName}）",
            destinationEditable = false,
            destinationLabel = "外部应用",
            conflicts = emptyList(),
            privileged = true, // 复用醒目警示位：文件将离开本应用
            undoable = false,
            bulk = items.size > AgentSettings.state.value.batchThreshold,
            extras = mapOf("plugin_id" to entry.id, "package" to entry.packageName)
        )
    }
}

/** 列出工具托盘插件目录及安装状态，供模型选择 plugin_id。 */
class ListPluginsTool : AgentTool {
    override val name = "list_plugins"
    override val description = "列出可通过 run_plugin_action 调用的外部工具插件及其安装状态。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject())

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val catalog = PluginCatalog.load(ctx)
            val pm = ctx.appContext.packageManager
            val arr = JSONArray()
            catalog.forEach { e ->
                val installed = try {
                    pm.getLaunchIntentForPackage(e.packageName) != null
                } catch (_: Throwable) {
                    false
                }
                arr.put(
                    JSONObject()
                        .put("plugin_id", e.id)
                        .put("name", e.name)
                        .put("package", e.packageName)
                        .put("description", e.description)
                        .put("installed", installed)
                )
            }
            ToolResult.ok(JSONObject().put("plugins", arr), "共 ${catalog.size} 个插件")
        }
}

/** assets/apk/index.json 的内存缓存。 */
object PluginCatalog {
    data class Entry(
        val id: String,
        val name: String,
        val packageName: String,
        val description: String
    )

    @Volatile
    private var cache: List<Entry>? = null

    fun load(ctx: ToolContext): List<Entry> = cache ?: synchronized(this) {
        cache ?: parse(ctx).also { cache = it }
    }

    /** id 精确匹配，其次包名匹配。 */
    fun resolve(entries: List<Entry>, pluginId: String): Entry? =
        entries.firstOrNull { it.id == pluginId }
            ?: entries.firstOrNull { it.packageName == pluginId }

    private fun parse(ctx: ToolContext): List<Entry> {
        return try {
            val json = ctx.appContext.assets.open("apk/index.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(json).optJSONArray("tools") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val pkg = o.optString("packageName")
                if (pkg.isEmpty()) null
                else Entry(
                    id = o.optString("id").ifEmpty { pkg },
                    name = o.optString("name", pkg),
                    packageName = pkg,
                    description = o.optString("description")
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

}
