package com.freefile.tools.ai.tool

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.freefile.operations.TransferManager
import com.freefile.tools.ai.model.ErrorCode
import com.freefile.tools.ai.model.ToolPermission
import com.freefile.tools.ai.model.ToolResult
import com.freefile.tools.ai.model.WritePreview
import com.freefile.tools.unzip.CompressionEngine
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.joinPath
import com.freefile.vfs.pathName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.freefile.tools.ai.tool.ToolArgs.bool
import com.freefile.tools.ai.tool.ToolArgs.int
import com.freefile.tools.ai.tool.ToolArgs.long
import com.freefile.tools.ai.tool.ToolArgs.str
import com.freefile.tools.ai.tool.ToolArgs.strList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** 压缩 / 解压 / 创作结果落盘工具。 */

private suspend fun awaitTask(enqueue: ((Boolean, String) -> Unit) -> Long): Pair<Boolean, String> =
    suspendCancellableCoroutine { cont ->
        val id = enqueue { ok, msg ->
            if (cont.isActive) cont.resume(ok to msg)
        }
        cont.invokeOnCancellation { TransferManager.cancel(id) }
    }

// ------------------------------------------------------------------ compress

class CompressTool : AgentTool {
    override val name = "compress"
    override val description =
        "把多个文件/文件夹打包压缩（zip/7z/tar/tar.gz/tar.bz2/tar.xz，可选压缩级别与 zip 分卷大小）。" +
            "不支持加密压缩包（项目内压缩功能不带密码）。写操作，需要确认，可撤销。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = true

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("destination", JSONObject().put("type", "string").put("description", "压缩包保存目录的绝对路径"))
                .put("archive_name", JSONObject().put("type", "string").put("description", "压缩包文件名（不含扩展名），不传则自动命名"))
                .put("format", JSONObject().put("type", "string").put("enum", JSONArray(listOf("zip", "7z", "tar", "tar.gz", "tar.bz2", "tar.xz"))))
                .put("level", JSONObject().put("type", "string").put("enum", JSONArray(listOf("store", "fast", "normal", "maximum"))))
                .put("split_mb", JSONObject().put("type", "integer").put("description", "分卷大小 MB，仅 zip 支持；0 不分卷"))
        )
        .put("required", JSONArray(listOf("paths", "destination")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            if (args.strOrNullCompat("password") != null) {
                return@withContext ToolResult.error(
                    ErrorCode.INVALID_ARGS,
                    "压缩工具不支持密码（加密压缩包不可用）。可压缩后再用加密能力处理文件。"
                )
            }
            val rawPaths = args.strList("paths").distinct()
            val rawDest = args.str("destination")
            if (rawPaths.isEmpty() || rawDest.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "需要 paths 与 destination")
            }
            val destCheck = PathGuard.checkWrite(ctx.appContext, rawDest)
            if (!destCheck.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, destCheck.reason ?: "")
            val destDir = destCheck.normalized

            // 压缩走 java.io，仅支持本地真实文件
            val localBad = rawPaths.filter { it.startsWith("remote://") || it.contains('#') }
            if (localBad.isNotEmpty()) {
                return@withContext ToolResult.error(
                    ErrorCode.INVALID_ARGS,
                    "远程位置/压缩包内文件不能直接打包，请先下载或解压到本地：${localBad.joinToString(";")}"
                )
            }
            val prepared = WriteSupport.prepareEntries(ctx, rawPaths)
            if (prepared.entries.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "条目均不存在：${prepared.missing.joinToString(";")}")
            }

            val format = when (args.str("format", "zip").lowercase()) {
                "zip" -> CompressionEngine.Format.ZIP
                "7z" -> CompressionEngine.Format.SEVEN_Z
                "tar" -> CompressionEngine.Format.TAR
                "tar.gz", "tgz" -> CompressionEngine.Format.TAR_GZ
                "tar.bz2" -> CompressionEngine.Format.TAR_BZ2
                "tar.xz" -> CompressionEngine.Format.TAR_XZ
                else -> return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "不支持的压缩格式")
            }
            val level = when (args.str("level", "normal").lowercase()) {
                "store" -> CompressionEngine.Level.STORE
                "fast" -> CompressionEngine.Level.FAST
                "normal" -> CompressionEngine.Level.NORMAL
                "maximum" -> CompressionEngine.Level.MAXIMUM
                else -> CompressionEngine.Level.NORMAL
            }
            val splitMb = args.long("split_mb").coerceAtLeast(0)
            if (splitMb > 0 && format != CompressionEngine.Format.ZIP) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "分卷仅支持 zip 格式")
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val baseName = args.str("archive_name").ifEmpty {
                if (prepared.entries.size == 1) {
                    pathName(prepared.entries.first().path).substringBeforeLast('.')
                } else "archive_$stamp"
            }
            val archivePath = joinPath(destDir, "$baseName.${format.extension}")
            if (File(archivePath).exists()) {
                return@withContext ToolResult.error(
                    ErrorCode.ALREADY_EXISTS,
                    "压缩包已存在：$archivePath，请更换 archive_name"
                )
            }

            val preview = WriteSupport.preview(
                toolName = name,
                title = "压缩打包",
                entries = prepared.entries,
                destination = destDir,
                destinationEditable = true,
                destinationLabel = "压缩包保存目录",
                conflicts = emptyList(),
                extras = mapOf(
                    "archive_path" to archivePath,
                    "format" to format.name,
                    "level" to level.name,
                    "split_mb" to splitMb.toString()
                )
            )
            val approval = ctx.gate.request(preview)
                ?: return@withContext ToolResult.cancelledByUser()
            if (approval.selectedPaths.isEmpty()) return@withContext ToolResult.cancelledByUser()

            // 用户可能调整了保存目录，重新计算落点
            val chosen = prepared.entries.filter { approval.selectedPaths.contains(it.path) }
            val finalDir = approval.destination.ifEmpty { destDir }
            val dirCheck = PathGuard.checkWrite(ctx.appContext, finalDir)
            if (!dirCheck.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, dirCheck.reason ?: "")
            val finalArchive = joinPath(dirCheck.normalized, "$baseName.${format.extension}")
            File(finalArchive).parentFile?.mkdirs()
            if (File(finalArchive).exists()) {
                return@withContext ToolResult.error(ErrorCode.ALREADY_EXISTS, "压缩包已存在：$finalArchive")
            }

            val options = CompressionEngine.Options(
                format = format, level = level, splitMb = splitMb
            )
            val (ok, msg) = awaitTask { cb ->
                TransferManager.enqueueCompress(chosen, finalArchive, options, cb)
            }
            WriteSupport.notifyDirs(dirCheck.normalized)
            if (!ok) return@withContext ToolResult.error(ErrorCode.IO_ERROR, "压缩失败：$msg")

            // 撤销范围：主包 + zip 分卷
            val produced = ArrayList<String>()
            produced.add(finalArchive)
            if (splitMb > 0) {
                val main = File(finalArchive)
                main.parentFile?.listFiles()?.forEach { f ->
                    val n = f.name
                    if (n.startsWith("$baseName.") && n.matches(Regex(""".*\.z\d+$"""))) produced.add(f.absolutePath)
                }
            }
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "压缩 ${chosen.size} 项 → $finalArchive（${format.name}/${level.name}）",
                msg.ifEmpty { "压缩完成" },
                chosen.size, 0, 0, true, ctx.undoManager.inverseToTrash(produced),
                dirCheck.normalized
            )
            ToolResult.ok(
                JSONObject()
                    .put("archive_path", finalArchive)
                    .put("format", format.name)
                    .put("parts", JSONArray(produced))
                    .put("size", File(finalArchive).length()),
                "压缩完成：${pathName(finalArchive)}"
            )
        }
}

// ------------------------------------------------------------------- extract

class ExtractTool : AgentTool {
    override val name = "extract"
    override val description =
        "解压压缩包（zip/7z/tar 等）到指定目录；不传 destination 时解压到压缩包旁的同名文件夹。写操作，需要确认，可撤销。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = true

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("archive", JSONObject().put("type", "string"))
                .put("destination", JSONObject().put("type", "string"))
        )
        .put("required", JSONArray(listOf("archive")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val rawArchive = args.str("archive")
            if (rawArchive.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 archive")
            if (rawArchive.contains('#') || rawArchive.startsWith("remote://")) {
                return@withContext ToolResult.error(
                    ErrorCode.INVALID_ARGS,
                    "请先把压缩包下载/放到本地真实路径再解压"
                )
            }
            val check = PathGuard.checkRead(ctx.appContext, rawArchive)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val archivePath = check.normalized
            val archiveEntry = EngineSelector.select(archivePath).stat(archivePath)
                ?: return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "压缩包不存在：$archivePath")

            val defaultDir = joinPath(
                archivePath.substringBeforeLast('/'),
                pathName(archivePath).substringBeforeLast('.')
            )
            val rawDest = args.str("destination").ifEmpty { defaultDir }
            val destCheck = PathGuard.checkWrite(ctx.appContext, rawDest)
            if (!destCheck.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, destCheck.reason ?: "")
            val destDir = destCheck.normalized

            val preview = WriteSupport.preview(
                toolName = name,
                title = "解压压缩包",
                entries = listOf(archiveEntry),
                destination = destDir,
                destinationEditable = true,
                destinationLabel = "解压到目录",
                conflicts = emptyList()
            )
            val approval = ctx.gate.request(preview)
                ?: return@withContext ToolResult.cancelledByUser()
            if (approval.selectedPaths.isEmpty()) return@withContext ToolResult.cancelledByUser()
            val finalDir = approval.destination.ifEmpty { destDir }
            val finalCheck = PathGuard.checkWrite(ctx.appContext, finalDir)
            if (!finalCheck.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, finalCheck.reason ?: "")
            val target = finalCheck.normalized

            val targetExistedBefore = File(target).exists()
            val startAt = System.currentTimeMillis()
            File(target).mkdirs()
            val (ok, msg) = awaitTask { cb ->
                TransferManager.enqueueExtract(archivePath, target, cb)
            }
            WriteSupport.notifyDirs(target)
            if (!ok) return@withContext ToolResult.error(ErrorCode.IO_ERROR, "解压失败：$msg")

            // 撤销：目标目录是本次新建的 → 整个进回收站；否则按时间窗收集新增文件
            val undoPaths = ArrayList<String>()
            if (!targetExistedBefore) {
                undoPaths.add(target)
            } else {
                File(target).walkTopDown().forEach { f ->
                    if (f.isFile && f.lastModified() >= startAt - 3000) undoPaths.add(f.absolutePath)
                }
            }
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "解压 $archivePath → $target",
                msg.ifEmpty { "解压完成" },
                undoPaths.size, 0, 0,
                undoPaths.isNotEmpty(),
                if (undoPaths.isNotEmpty()) ctx.undoManager.inverseToTrash(undoPaths) else "",
                target
            )
            ToolResult.ok(
                JSONObject()
                    .put("archive", archivePath)
                    .put("destination", target)
                    .put("created_count", undoPaths.size)
                    .put("undo_note", if (undoPaths.isEmpty()) "目标目录已存在且未能识别新增项，请手动清理" else "可一键撤销（移入回收站）"),
                "已解压到：$target"
            )
        }
}

// ------------------------------------------------------------- write_text_file

class WriteTextFileTool : AgentTool {
    override val name = "write_text_file"
    override val description =
        "把文本内容保存为文件（txt/md/html/pdf），用于把创作、周报、摘要等结果落盘。" +
            "同名文件冲突需指定 conflict：rename（默认）/skip/overwrite（覆盖必须用户确认）。写操作，需要确认，可撤销。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = true

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("directory", JSONObject().put("type", "string").put("description", "保存目录绝对路径"))
                .put("filename", JSONObject().put("type", "string").put("description", "文件名，可含扩展名"))
                .put("path", JSONObject().put("type", "string").put("description", "完整目标路径（提供时忽略 directory/filename）"))
                .put("content", JSONObject().put("type", "string").put("description", "文件文本内容"))
                .put("format", JSONObject().put("type", "string").put("enum", JSONArray(listOf("txt", "md", "html", "pdf"))))
                .put("conflict", JSONObject().put("type", "string").put("enum", JSONArray(listOf("rename", "skip", "overwrite"))))
        )
        .put("required", JSONArray(listOf("content")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val content = args.rawContent()
            if (content.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 content")

            var target = args.str("path")
            if (target.isEmpty()) {
                val dir = args.str("directory")
                val name = args.str("filename")
                if (dir.isEmpty() || name.isEmpty()) {
                    return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "需要 path，或同时提供 directory 与 filename")
                }
                target = joinPath(dir, name)
            }
            val check = PathGuard.checkWrite(ctx.appContext, target)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            target = check.normalized

            val ext = target.substringAfterLast('.', "").lowercase()
            val format = args.str("format").ifEmpty {
                if (ext in setOf("txt", "md", "html", "htm", "pdf")) if (ext == "htm") "html" else ext else "txt"
            }
            if (format !in setOf("txt", "md", "html", "pdf")) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "仅支持 txt/md/html/pdf")
            }
            // 扩展名与格式不一致时纠正扩展名
            if (ext !in setOf(format, "htm")) {
                target = "$target.$format"
            }
            val policy = when (args.str("conflict", WritePreview.CONFLICT_RENAME)) {
                WritePreview.CONFLICT_SKIP -> WritePreview.CONFLICT_SKIP
                WritePreview.CONFLICT_OVERWRITE -> WritePreview.CONFLICT_OVERWRITE
                else -> WritePreview.CONFLICT_RENAME
            }
            val engine = EngineSelector.select(target)
            val exists = engine.exists(target)
            val conflicts = if (exists) listOf(pathName(target)) else emptyList()

            val virtual = VfsEntry(
                path = target,
                name = pathName(target),
                isDirectory = false,
                size = content.toByteArray(Charsets.UTF_8).size.toLong()
            )
            val parent = target.substringBeforeLast('/').ifEmpty { "/" }
            val preview = WriteSupport.preview(
                toolName = name,
                title = "保存创作结果",
                entries = listOf(virtual),
                destination = parent,
                destinationEditable = false,
                destinationLabel = "保存目录",
                conflicts = conflicts,
                extras = mapOf("format" to format)
            )
            val approval = ctx.gate.request(preview)
                ?: return@withContext ToolResult.cancelledByUser()
            if (approval.selectedPaths.isEmpty()) return@withContext ToolResult.cancelledByUser()
            if (approval.conflictPolicy == WritePreview.CONFLICT_OVERWRITE && !approval.overwriteAcknowledged) {
                return@withContext ToolResult.error(ErrorCode.PERMISSION_DENIED, "覆盖需要用户明确勾选确认")
            }

            var finalPath = target
            val finalExists = engine.exists(finalPath)
            if (finalExists) {
                when (approval.conflictPolicy) {
                    WritePreview.CONFLICT_SKIP ->
                        return@withContext ToolResult.ok(
                            JSONObject().put("skipped", true).put("path", finalPath),
                            "文件已存在，已跳过"
                        )
                    WritePreview.CONFLICT_OVERWRITE -> engine.delete(finalPath, recursive = false)
                    else -> {
                        val desired = pathName(finalPath)
                        val dot = desired.lastIndexOf('.')
                        val base = if (dot > 0) desired.substring(0, dot) else desired
                        val suffix = if (dot > 0) desired.substring(dot) else ""
                        finalPath = joinPath(parent, WriteSupport.uniqueName(engine, parent, "$base$suffix"))
                    }
                }
            }

            try {
                engine.openOutputStream(finalPath).use { out ->
                    if (format == "pdf") writePdf(content, out)
                    else out.write(content.toByteArray(Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                return@withContext ToolResult.error(ErrorCode.IO_ERROR, "写入失败：${t.message}")
            }
            WriteSupport.notifyDirs(parent)
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "保存文件：$finalPath（$format，${content.length} 字符）",
                "保存成功", 1, 0, 0, true,
                ctx.undoManager.inverseToTrash(listOf(finalPath)), parent
            )
            ToolResult.ok(
                JSONObject().put("path", finalPath).put("format", format).put("chars", content.length),
                "已保存：${pathName(finalPath)}"
            )
        }

    /** 极简 PDF 排版：A4、自动换行分页。 */
    private fun writePdf(text: String, out: java.io.OutputStream) {
        val doc = PdfDocument()
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isAntiAlias = true
        }
        val pageW = 595
        val pageH = 842
        val margin = 48
        val usable = (pageW - margin * 2).toFloat()
        val lineHeight = 16f

        val lines = ArrayList<String>()
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) {
                lines.add("")
                return@forEach
            }
            var idx = 0
            while (idx < line.length) {
                val count = paint.breakText(line, idx, line.length, true, usable, null)
                lines.add(line.substring(idx, idx + count))
                idx += count
            }
        }
        val perPage = ((pageH - margin * 2) / lineHeight).toInt()
        var pageNo = 1
        var i = 0
        while (i < lines.size) {
            val builder = PdfDocument.PageInfo.Builder(pageW, pageH, pageNo)
            val page = doc.startPage(builder.create())
            var y = margin.toFloat() + lineHeight
            val end = minOf(i + perPage, lines.size)
            for (j in i until end) {
                page.canvas.drawText(lines[j], margin.toFloat(), y, paint)
                y += lineHeight
            }
            doc.finishPage(page)
            i = end
            pageNo++
        }
        try {
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}

/** 取原始 content 字段（不 trim，保留创作排版）。 */
private fun JSONObject.rawContent(): String =
    if (has("content") && !isNull("content")) optString("content") else ""

private fun JSONObject.strOrNullCompat(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null
