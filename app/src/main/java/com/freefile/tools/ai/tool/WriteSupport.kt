package com.freefile.tools.ai.tool

import com.freefile.tools.ai.data.AgentSettings
import com.freefile.tools.ai.model.PreviewItem
import com.freefile.tools.ai.model.ToolProgress
import com.freefile.tools.ai.model.WritePreview
import com.freefile.vfs.FileEngine
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.joinPath

/**
 * 写工具公共助手：条目预检、唯一命名、预览构造、逐项传输执行。
 * 所有批量写操作均逐项执行并记账，部分失败不影响其他项。
 */
object WriteSupport {

    data class Prepared(
        val entries: List<VfsEntry>,
        val missing: List<String>
    )

    /** 逐个路径做读校验 + stat，返回存在条目与缺失路径。 */
    suspend fun prepareEntries(ctx: ToolContext, paths: List<String>): Prepared {
        val ok = ArrayList<VfsEntry>()
        val missing = ArrayList<String>()
        for (raw in paths) {
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) {
                missing.add("$raw（${check.reason}）")
                continue
            }
            val entry = try {
                com.freefile.vfs.EngineSelector.select(check.normalized).stat(check.normalized)
            } catch (_: Throwable) {
                null
            }
            if (entry == null) missing.add(raw) else ok.add(entry)
        }
        return Prepared(ok, missing)
    }

    fun PreviewItem(
        entries: List<VfsEntry>,
        selectedPaths: Set<String>? = null
    ): List<PreviewItem> = entries.map {
        PreviewItem(
            path = it.path,
            name = it.name,
            size = it.size,
            isDirectory = it.isDirectory,
            selected = selectedPaths?.contains(it.path) ?: true
        )
    }

    /** 判断路径是否将通过特权引擎（root/Shizuku shell）执行。 */
    fun privilegedOf(path: String): Boolean {
        val id = try {
            com.freefile.vfs.EngineSelector.select(path).id
        } catch (_: Throwable) {
            "local"
        }
        return id != "local" && id != "archive" && id != "remote"
    }

    /**
     * 生成目标目录内不冲突的名字：name (1).ext / name (2).ext…
     * 与 FileOperations 内部规则保持一致，保证执行前预测的落点准确。
     */
    suspend fun uniqueName(engine: FileEngine, dir: String, desired: String): String {
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var i = 1
        while (i < 10000) {
            val candidate = "$base ($i)$ext"
            if (!engine.exists(joinPath(dir, candidate))) return candidate
            i++
        }
        return "$desired.$i"
    }

    /**
     * 逐项复制/移动，返回每项最终落点（用于精确撤销）。
     * 冲突按 [policy] 处置；overwrite 必须 [overwriteAcknowledged]。
     */
    data class Landed(val source: String, val dest: String)
    data class TransferOutcome(
        val landed: List<Landed>,
        val failed: List<Pair<String, String>>,
        val skipped: List<String>
    )

    suspend fun runTransfer(
        ctx: ToolContext,
        isMove: Boolean,
        entries: List<VfsEntry>,
        destDir: String,
        policy: String,
        overwriteAcknowledged: Boolean,
        toolName: String
    ): TransferOutcome {
        val landed = ArrayList<Landed>()
        val failed = ArrayList<Pair<String, String>>()
        val skipped = ArrayList<String>()
        val total = entries.size

        entries.forEachIndexed { index, entry ->
            if (ctx.isCancelled()) {
                failed.add(entry.path to "用户取消")
                return@forEachIndexed
            }
            ctx.onProgress(
                ToolProgress(
                    toolName = toolName,
                    filesDone = index,
                    totalFiles = total,
                    current = entry.path
                )
            )
            try {
                val dstEngine = com.freefile.vfs.EngineSelector.select(destDir)
                if (!dstEngine.exists(destDir)) dstEngine.mkdirs(destDir)
                val conflict = dstEngine.exists(joinPath(destDir, entry.name))
                var finalName = entry.name
                when {
                    conflict && policy == WritePreview.CONFLICT_SKIP -> {
                        skipped.add(entry.path)
                        return@forEachIndexed
                    }
                    conflict && policy == WritePreview.CONFLICT_OVERWRITE -> {
                        if (!overwriteAcknowledged) {
                            failed.add(entry.path to "覆盖未获用户确认")
                            return@forEachIndexed
                        }
                        dstEngine.delete(joinPath(destDir, entry.name), recursive = true)
                    }
                    conflict -> {
                        finalName = uniqueName(dstEngine, destDir, entry.name)
                    }
                }
                if (isMove) {
                    // 同引擎优先 rename（瞬时移动）；失败回退 复制+删除（跨卷）
                    val srcEngine = com.freefile.vfs.EngineSelector.select(entry.path)
                    val targetPath = joinPath(destDir, finalName)
                    val renamed = try {
                        srcEngine.rename(entry.path, targetPath)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!renamed) {
                        com.freefile.operations.FileOperations.copy(
                            listOf(entry), destDir,
                            onProgress = { _, _, _, _, _ -> }
                        )
                        srcEngine.delete(entry.path, recursive = true)
                        if (finalName != entry.name) {
                            val copied = joinPath(destDir, entry.name)
                            if (dstEngine.exists(copied)) dstEngine.rename(copied, targetPath)
                        }
                    }
                    landed.add(Landed(entry.path, targetPath))
                } else {
                    // 复制：rename 冲突项需要先按新名字构造一个临时改名源不现实，
                    // 因此冲突重命名时采用 复制到临时名 再 rename 到最终名 的等价做法。
                    val targetPath = joinPath(destDir, finalName)
                    if (finalName == entry.name) {
                        com.freefile.operations.FileOperations.copy(
                            listOf(entry), destDir,
                            onProgress = { f, t, b, tb, cur ->
                                ctx.onProgress(
                                    ToolProgress(toolName, index + f, total, b, tb, cur)
                                )
                            },
                            isCancelled = ctx.isCancelled
                        )
                    } else {
                        copyWithRename(entry, targetPath)
                    }
                    landed.add(Landed(entry.path, targetPath))
                }
            } catch (t: Throwable) {
                failed.add(entry.path to (t.message ?: "IO 错误"))
            }
        }
        ctx.onProgress(ToolProgress(toolName, total, total, current = ""))
        return TransferOutcome(landed, failed, skipped)
    }

    /** 跨引擎/改名复制：展平目录后按相对路径写入目标。 */
    private suspend fun copyWithRename(entry: VfsEntry, targetPath: String) {
        val srcEngine = com.freefile.vfs.EngineSelector.select(entry.path)
        val dstEngine = com.freefile.vfs.EngineSelector.select(targetPath)
        if (entry.isDirectory) {
            dstEngine.mkdirs(targetPath)
            val flat = com.freefile.operations.FileOperations.flatten(entry)
            for (child in flat.drop(1)) {
                val rel = child.path.removePrefix(entry.path).trimStart('/')
                val cDest = joinPath(targetPath, rel)
                if (child.isDirectory) dstEngine.mkdirs(cDest)
                else com.freefile.operations.FileOperations.streamCopy(
                    srcEngine.openInputStream(child.path),
                    dstEngine.openOutputStream(cDest)
                )
            }
        } else {
            dstEngine.openOutputStream(targetPath).use { out ->
                srcEngine.openInputStream(entry.path).use { it.copyTo(out) }
            }
        }
    }

    /** 构造批量写操作预览（应用批量阈值/特权标记）。 */
    fun preview(
        toolName: String,
        title: String,
        entries: List<VfsEntry>,
        destination: String,
        destinationEditable: Boolean,
        destinationLabel: String,
        conflicts: List<String>,
        destructive: Boolean = false,
        undoable: Boolean = true,
        extras: Map<String, String> = emptyMap()
    ): WritePreview {
        val threshold = AgentSettings.state.value.batchThreshold
        val privileged = entries.any { privilegedOf(it.path) } ||
            (destination.isNotBlank() && runCatching { privilegedOf(destination) }.getOrDefault(false))
        return WritePreview(
            token = UUID0.next(),
            toolName = toolName,
            title = title,
            items = PreviewItem(entries),
            destination = destination,
            destinationEditable = destinationEditable,
            destinationLabel = destinationLabel,
            conflicts = conflicts,
            privileged = privileged,
            destructive = destructive,
            undoable = undoable,
            bulk = entries.size > threshold,
            extras = extras
        )
    }

    fun notifyDirs(vararg dirs: String) {
        dirs.filter { it.isNotBlank() }.distinct().forEach {
            com.freefile.operations.FileChangeBus.notify(it)
        }
    }
}

/** 避免在多处直接引用 UUID 的小工具。 */
private object UUID0 {
    fun next(): String = java.util.UUID.randomUUID().toString()
}
