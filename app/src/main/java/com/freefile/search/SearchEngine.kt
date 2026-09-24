package com.freefile.search

import android.content.Context
import com.freefile.data.db.FreeFileDatabase
import com.freefile.data.db.SearchIndexEntry
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.FileEngine
import com.freefile.vfs.VfsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

/**
 * 搜索选项。
 *
 * 基础：
 *  - [searchDepth] 搜索深度：0 = 仅当前目录；-1 = 无限递归；N(>0) = 最多递归 N 层。
 *  - [advanced] 是否启用高级搜索（AND 关系：文件名 + 大小 + 时间 + 内容）。
 *  - [regex] 文件名按正则解析；否则按通配符（* 和 ?）。
 *  - [caseSensitive] 区分大小写。
 *
 * 高级（仅当 [advanced]=true 时生效）：
 *  - [sizeOp] / [sizeBytes] 按文件大小过滤。
 *  - [timeFrom] / [timeTo] 按修改时间过滤（0 表示不限）。
 *  - [contentPattern] 文件内容包含（文本）；为空跳过内容检查。
 *  - [useIndex] 高级搜索时是否优先使用索引（索引未就绪自动回退实时）。
 */
data class SearchOptions(
    val searchDepth: Int = -1,
    val advanced: Boolean = false,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val sizeOp: SizeOp = SizeOp.NONE,
    val sizeBytes: Long = 0,
    val timeFrom: Long = 0,
    val timeTo: Long = 0,
    val contentPattern: String = "",
    val useIndex: Boolean = false
) {
    /** 是否递归子目录（深度 != 0）。 */
    val searchSubDirs: Boolean get() = searchDepth != 0
}

/** 文件大小比较操作符。 */
enum class SizeOp { NONE, GE, LE, GT, LT, EQ }

/** 索引状态。 */
sealed class IndexState {
    object Idle : IndexState()
    data class Building(val scanned: Int) : IndexState()
    data class Ready(val total: Int) : IndexState()
}

/**
 * 搜索引擎：
 * - 实时模式：按 [SearchOptions.searchDepth] 控制递归深度遍历目录
 * - 索引模式：后台全量扫描入 Room，搜索时查库（秒出）
 *
 * 高级搜索条件之间为 AND 关系。
 */
object SearchEngine {

    private val _indexState = MutableStateFlow<IndexState>(IndexState.Idle)
    val indexState: StateFlow<IndexState> = _indexState.asStateFlow()

    /**
     * 启动时从数据库恢复索引就绪状态：若 Room 中已有索引数据，则直接标记为 Ready，
     * 避免应用重启后索引仍显示"未就绪"而要求用户重新构建。
     */
    suspend fun restoreIndexState(context: Context) = withContext(Dispatchers.IO) {
        val count = FreeFileDatabase.get(context).searchIndexDao().count()
        _indexState.value = if (count > 0) IndexState.Ready(count) else IndexState.Idle
    }

    /**
     * 文件名匹配：默认按通配符（* 和 ?），勾选正则时按正则解析。
     */
    fun matches(name: String, query: String, opts: SearchOptions): Boolean {
        if (query.isEmpty()) return true
        return when {
            opts.regex -> try {
                val r = if (opts.caseSensitive) Regex(query)
                else Regex(query, RegexOption.IGNORE_CASE)
                r.containsMatchIn(name)
            } catch (_: Exception) { false }
            else -> wildcardMatch(name, query, opts.caseSensitive)
        }
    }

    /**
     * 通配符匹配：* 匹配任意长度字符（含空），? 匹配单字符。
     * 转换为等价正则后整串匹配。
     */
    private fun wildcardMatch(name: String, pattern: String, caseSensitive: Boolean): Boolean {
        val sb = StringBuilder("^")
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                in "\\^$.|+(){}[]" -> sb.append("\\").append(c)
                else -> sb.append(c)
            }
        }
        sb.append("$")
        return try {
            val r = if (caseSensitive) Regex(sb.toString())
            else Regex(sb.toString(), RegexOption.IGNORE_CASE)
            r.containsMatchIn(name)
        } catch (_: Exception) { false }
    }

    // ==================== 高级条件过滤（共享，避免重复实现） ====================

    /**
     * 大小 / 时间条件过滤（不含文件名与内容）。
     * 实时搜索与索引搜索共用，避免逻辑分叉。
     */
    private fun passesMetaFilter(entry: VfsEntry, opts: SearchOptions): Boolean {
        if (!opts.advanced) return true
        if (opts.sizeOp != SizeOp.NONE && !entry.isDirectory) {
            val sz = entry.size
            val ok = when (opts.sizeOp) {
                SizeOp.GE -> sz >= opts.sizeBytes
                SizeOp.LE -> sz <= opts.sizeBytes
                SizeOp.GT -> sz > opts.sizeBytes
                SizeOp.LT -> sz < opts.sizeBytes
                SizeOp.EQ -> sz == opts.sizeBytes
                SizeOp.NONE -> true
            }
            if (!ok) return false
        }
        if (opts.timeFrom > 0 && entry.lastModified < opts.timeFrom) return false
        if (opts.timeTo > 0 && entry.lastModified > opts.timeTo) return false
        return true
    }

    /**
     * 综合条目匹配（AND 关系）：文件名 + 大小 + 修改时间 + 内容。
     * 高级搜索关闭时仅匹配文件名。
     */
    suspend fun entryMatches(
        engine: FileEngine,
        entry: VfsEntry,
        query: String,
        opts: SearchOptions
    ): Boolean {
        if (!matches(entry.name, query, opts)) return false
        if (!passesMetaFilter(entry, opts)) return false

        // 内容搜索（仅文件，限制 4MB 内文本，避免内存压力）
        if (opts.advanced && opts.contentPattern.isNotBlank() && !entry.isDirectory) {
            val hasContent = try {
                engine.openInputStream(entry.path).use { input ->
                    val raw = input.readBytes()
                    val limit = if (raw.size > 4 * 1024 * 1024) raw.copyOf(4 * 1024 * 1024) else raw
                    val text = String(limit, Charsets.UTF_8)
                    if (opts.regex) {
                        val r = if (opts.caseSensitive) Regex(opts.contentPattern)
                        else Regex(opts.contentPattern, RegexOption.IGNORE_CASE)
                        r.containsMatchIn(text)
                    } else {
                        text.contains(opts.contentPattern, ignoreCase = !opts.caseSensitive)
                    }
                }
            } catch (_: Exception) { false }
            if (!hasContent) return false
        }
        return true
    }

    // ==================== 实时搜索 ====================

    /**
     * 实时递归搜索：从 root 开始按 BFS 遍历。
     * [SearchOptions.searchDepth] 控制递归深度：
     *   0  → 仅当前目录（不进子目录）
     *   -1 → 无限递归
     *   N  → 最多 N 层
     * 上限 1000 条结果 + 200000 节点访问兜底，防止卡顿。
     */
    suspend fun searchLive(
        root: String,
        query: String,
        opts: SearchOptions,
        isCancelled: () -> Boolean = { false }
    ): List<VfsEntry> = withContext(Dispatchers.IO) {
        val results = ArrayList<VfsEntry>()
        val engine = EngineSelector.select(root)
        data class Node(val path: String, val level: Int)
        val queue = ArrayDeque<Node>()
        queue.add(Node(root, 0))
        var visited = 0
        val unlimited = opts.searchDepth < 0
        while (queue.isNotEmpty() && results.size < 1000 && !isCancelled()) {
            val node = queue.poll()
            if (++visited > 200000) break
            val children = try { engine.list(node.path) } catch (_: Exception) { continue }
            for (c in children) {
                if (entryMatches(engine, c, query, opts)) results.add(c)
                // 深度控制：下一层 level+1 必须 <= searchDepth（无限时跳过判断）
                if (c.isDirectory && (unlimited || node.level + 1 <= opts.searchDepth)) {
                    queue.add(Node(c.path, node.level + 1))
                }
            }
        }
        results
    }

    // ==================== 索引搜索 ====================

    /**
     * 索引搜索：查库后内存过滤（文件名 + 大小 + 时间）。
     * 内容搜索无法走索引，调用方应在高级 + 内容模式下回退到 [searchLive]。
     */
    suspend fun searchIndexed(
        context: Context,
        query: String,
        opts: SearchOptions
    ): List<VfsEntry> = withContext(Dispatchers.IO) {
        val dao = FreeFileDatabase.get(context).searchIndexDao()
        val rows: List<SearchIndexEntry> = if (opts.regex) {
            dao.loadAll().filter { matches(it.name, query, opts) }
        } else {
            dao.searchFuzzy(query).filter { matches(it.name, query, opts) }
        }
        rows.map {
            VfsEntry(
                path = it.path,
                name = it.name,
                isDirectory = it.isDirectory,
                size = it.size,
                lastModified = it.lastModified,
                isHidden = it.name.startsWith(".")
            )
        }.filter { entry -> passesMetaFilter(entry, opts) }
    }

    // ==================== 索引构建 ====================

    private const val INDEX_MAX_ITEMS = 500_000
    private const val INDEX_MAX_DEPTH = 20
    private const val INDEX_BATCH_SIZE = 500
    /** 索引状态更新频率（每扫描 N 条更新一次，避免每文件触发状态流）。 */
    private const val INDEX_STATE_INTERVAL = 100

    /**
     * 后台构建索引：全量扫描内部存储主根目录，清空重建。
     * 使用 java.io.File 直接扫描（本地索引无需走 VFS shell 开销）。
     * 状态更新按 [INDEX_STATE_INTERVAL] 批量推送，避免 50 万次状态流刷新。
     */
    suspend fun buildIndex(context: Context) = withContext(Dispatchers.IO) {
        _indexState.value = IndexState.Building(0)
        val dao = FreeFileDatabase.get(context).searchIndexDao()
        dao.clear()
        var total = 0
        var lastReported = 0
        val batch = ArrayList<SearchIndexEntry>(INDEX_BATCH_SIZE)
        suspend fun flush() {
            if (batch.isEmpty()) return
            dao.insertAll(ArrayList(batch))
            batch.clear()
        }
        suspend fun walk(dir: File, level: Int) {
            if (total >= INDEX_MAX_ITEMS || level > INDEX_MAX_DEPTH) return
            val files = try { dir.listFiles() } catch (_: Exception) { return } ?: return
            for (f in files) {
                if (total >= INDEX_MAX_ITEMS) return
                batch.add(
                    SearchIndexEntry(
                        path = f.absolutePath,
                        name = f.name,
                        parent = dir.absolutePath,
                        size = if (f.isFile) f.length() else 0,
                        lastModified = f.lastModified(),
                        isDirectory = f.isDirectory
                    )
                )
                total++
                if (batch.size >= INDEX_BATCH_SIZE) flush()
                if (total - lastReported >= INDEX_STATE_INTERVAL) {
                    lastReported = total
                    _indexState.value = IndexState.Building(total)
                }
                if (f.isDirectory) walk(f, level + 1)
            }
        }
        for (root in listOf(
            File("/storage/emulated/0"),
            File("/sdcard").takeIf { it.exists() && it.absolutePath != "/storage/emulated/0" }
        ).filterNotNull().distinctBy { it.absolutePath }) {
            walk(root, 0)
        }
        flush()
        _indexState.value = IndexState.Ready(total)
    }

    /** 清空索引（设置页关闭索引缓存开关时调用）。 */
    suspend fun clearIndex(context: Context) = withContext(Dispatchers.IO) {
        FreeFileDatabase.get(context).searchIndexDao().clear()
        _indexState.value = IndexState.Idle
    }
}
