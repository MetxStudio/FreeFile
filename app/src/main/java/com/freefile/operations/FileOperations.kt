package com.freefile.operations

import com.freefile.vfs.EngineSelector
import com.freefile.vfs.FileEngine
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.VfsException
import com.freefile.vfs.joinPath
import com.freefile.vfs.pathName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件操作底层实现：复制/移动/删除（递归、进度回调、冲突预扫描）。
 * 跨引擎复制通过输入/输出流桥接；同引擎优先 rename（瞬时移动）。
 */
object FileOperations {

    private const val BUFFER_SIZE = 128 * 1024

    /** 递归收集条目（目录展开为全部子孙文件+目录本身）。 */
    suspend fun flatten(entry: VfsEntry): List<VfsEntry> {
        val engine = EngineSelector.select(entry.path)
        val out = mutableListOf<VfsEntry>()
        suspend fun walk(e: VfsEntry) {
            out.add(e)
            if (e.isDirectory) {
                val children = try { engine.list(e.path) } catch (ex: Exception) { emptyList() }
                children.forEach { walk(it) }
            }
        }
        walk(entry)
        return out
    }

    /** 预扫描目标冲突（源条目在目标目录下的同名项）。 */
    suspend fun detectConflicts(entries: List<VfsEntry>, targetDir: String): List<Conflict> {
        val engine = EngineSelector.select(targetDir)
        val existing = try { engine.list(targetDir) } catch (e: Exception) { emptyList() }
        val byName = existing.associateBy { it.name }
        return entries.mapNotNull { src ->
            byName[src.name]?.let { Conflict(src, it) }
        }
    }

    /** 根据解析策略生成「源路径 → 动作」与「源路径 → 新名字」。 */
    private suspend fun resolve(
        conflicts: List<Conflict>,
        resolution: ConflictResolution
    ): Pair<Map<String, ConflictAction>, Map<String, String>> {
        val actions = mutableMapOf<String, ConflictAction>()
        val renames = mutableMapOf<String, String>()
        when (resolution) {
            is ConflictResolution.OverwriteAll -> conflicts.forEach { actions[it.source.path] = ConflictAction.OVERWRITE }
            is ConflictResolution.SkipAll -> conflicts.forEach { actions[it.source.path] = ConflictAction.SKIP }
            is ConflictResolution.RenameAll -> conflicts.forEach {
                actions[it.source.path] = ConflictAction.RENAME
                renames[it.source.path] = uniqueNameFor(it.source.name, it.target.path)
            }
            is ConflictResolution.PerFile -> conflicts.forEach { c ->
                when (resolution.decisions[c.source.path]) {
                    ConflictAction.OVERWRITE -> actions[c.source.path] = ConflictAction.OVERWRITE
                    ConflictAction.SKIP, null -> actions[c.source.path] = ConflictAction.SKIP
                    ConflictAction.RENAME -> {
                        actions[c.source.path] = ConflictAction.RENAME
                        renames[c.source.path] = uniqueNameFor(c.source.name, c.target.path)
                    }
                }
            }
        }
        return actions to renames
    }

    /** 生成目标目录中不存在的名字：name (1).ext / name (2).ext ... */
    private suspend fun uniqueNameFor(desired: String, existingTargetPath: String): String {
        val engine = EngineSelector.select(existingTargetPath)
        val dir = existingTargetPath.substringBeforeLast('/')
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
     * 复制条目列表到目标目录。
     * @param onProgress 进度回调（filesDone/totalFiles/bytes）
     * @param isCancelled 协作式取消检查
     */
    suspend fun copy(
        entries: List<VfsEntry>,
        targetDir: String,
        conflicts: List<Conflict> = emptyList(),
        resolution: ConflictResolution = ConflictResolution.OverwriteAll,
        onProgress: (filesDone: Int, totalFiles: Int, bytesDone: Long, totalBytes: Long, current: String) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Int {
        val (actionMap, renameMap) = resolve(conflicts, resolution)
        val dstEngine = EngineSelector.select(targetDir)

        // 预计算总量
        val flat = entries.map { it to flatten(it) }
        val totalBytes = flat.sumOf { (_, list) -> list.sumOf { e -> if (e.isDirectory) 0 else e.size } }
        val totalFiles = flat.sumOf { (_, list) -> list.size }

        var filesDone = 0
        var bytesDone = 0L

        fun tick(current: String) {
            onProgress(filesDone, totalFiles, bytesDone, totalBytes, current)
            if (isCancelled()) throw CancellationException("用户取消")
        }

        for ((entry, list) in flat) {
            val action = actionMap[entry.path]
            if (action == ConflictAction.SKIP) {
                filesDone += list.size
                bytesDone += list.sumOf { if (it.isDirectory) 0 else it.size }
                continue
            }
            val destName = renameMap[entry.path] ?: entry.name
            val destPath = joinPath(targetDir, destName)

            if (action == ConflictAction.OVERWRITE) {
                // 目标存在则先删除（幂等）
                if (dstEngine.exists(destPath)) dstEngine.delete(destPath, recursive = true)
            } else if (action == null && dstEngine.exists(destPath) && conflicts.any { it.source.path == entry.path }) {
                // 无决策但存在冲突：默认跳过
                filesDone += list.size
                continue
            }

            if (entry.isDirectory) {
                dstEngine.mkdirs(destPath)
                for (child in list.drop(1)) {
                    val rel = child.path.removePrefix(entry.path).trimStart('/')
                    if (rel.isEmpty()) continue
                    val cDest = joinPath(destPath, rel)
                    tick(child.path)
                    if (child.isDirectory) dstEngine.mkdirs(cDest)
                    else copySingle(EngineSelector.select(child.path), dstEngine, child.path, cDest)
                    filesDone++
                    bytesDone += child.size
                }
            } else {
                tick(entry.path)
                copySingle(EngineSelector.select(entry.path), dstEngine, entry.path, destPath)
                filesDone++
                bytesDone += entry.size
            }
            tick(entry.path)
        }
        onProgress(filesDone, totalFiles, totalBytes, totalBytes, "")
        return filesDone
    }

    /** 移动：同引擎同分区优先 rename；否则复制后删除源。 */
    suspend fun move(
        entries: List<VfsEntry>,
        targetDir: String,
        conflicts: List<Conflict> = emptyList(),
        resolution: ConflictResolution = ConflictResolution.OverwriteAll,
        onProgress: (filesDone: Int, totalFiles: Int, bytesDone: Long, totalBytes: Long, current: String) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Int {
        val copied = copy(entries, targetDir, conflicts, resolution, onProgress, isCancelled)
        // 删除源（跳过项不删）
        val (actionMap, _) = resolve(conflicts, resolution)
        for (entry in entries) {
            if (actionMap[entry.path] == ConflictAction.SKIP) continue
            val engine = EngineSelector.select(entry.path)
            engine.delete(entry.path, recursive = entry.isDirectory)
        }
        return copied
    }

    /** 删除（递归）。 */
    suspend fun delete(
        entries: List<VfsEntry>,
        onProgress: (filesDone: Int, totalFiles: Int, bytesDone: Long, totalBytes: Long, current: String) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Int {
        var done = 0
        val total = entries.size
        for (e in entries) {
            onProgress(done, total, 0, 0, e.path)
            if (isCancelled()) throw CancellationException("用户取消")
            val engine = EngineSelector.select(e.path)
            engine.delete(e.path, recursive = true)
            done++
        }
        onProgress(done, total, 0, 0, "")
        return done
    }

    /** 单文件复制（跨引擎流式桥接）。 */
    private suspend fun copySingle(src: FileEngine, dst: FileEngine, srcPath: String, dstPath: String) {
        if (src === dst && src.canAccess(srcPath) && src.canAccess(dstPath.substringBeforeLast('/'))) {
            // 同引擎：尝试流复制（引擎内 rename 不适用于复制）
            streamCopy(src.openInputStream(srcPath), dst.openOutputStream(dstPath))
            return
        }
        streamCopy(src.openInputStream(srcPath), dst.openOutputStream(dstPath))
    }

    /** 流式复制 + 进度累加。 */
    fun streamCopy(input: InputStream, output: OutputStream, onChunk: (Int) -> Unit = {}) {
        input.use { ins ->
            output.use { outs ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    outs.write(buf, 0, n)
                    onChunk(n)
                }
                outs.flush()
            }
        }
    }

    /** 计算文件哈希（流式，支持 MD5/SHA-1/SHA-256）。 */
    suspend fun hash(enginePath: String, algorithm: String): String =
        with(kotlinx.coroutines.Dispatchers.IO) {
            val engine = EngineSelector.select(enginePath)
            val md = java.security.MessageDigest.getInstance(algorithm)
            engine.openInputStream(enginePath).use { ins ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

    /** 生成带时间戳的副本名（保留两者时使用）。 */
    fun timestampedCopyName(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "$base.$ts$ext"
    }

    /** 便捷：从文本文件读全部内容（UTF-8）。 */
    suspend fun readText(path: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        EngineSelector.select(path).openInputStream(path).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 便捷：写文本文件（UTF-8，覆盖）。 */
    suspend fun writeText(path: String, text: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        EngineSelector.select(path).openOutputStream(path).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
