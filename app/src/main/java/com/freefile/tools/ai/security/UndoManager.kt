package com.freefile.tools.ai.security

import android.content.Context
import com.freefile.data.db.FreeFileDatabase
import com.freefile.data.repo.Repository
import com.freefile.tools.ai.data.AgentOpLogEntity
import com.freefile.tools.ai.data.AgentRepository
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.joinPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 撤销结果。 */
data class UndoReport(
    val undone: Int,
    val failed: Int,
    val details: List<String>,
    val affectedDir: String
)

/**
 * 撤销管理器：写操作执行成功后写入操作日志（含逆操作 JSON），
 * 用户点"撤销"时按记录回放逆操作。逐项记账，部分失败如实上报。
 *
 * 逆操作类型：
 *  - restore_trash：把路径对应的回收站条目恢复（撤销 delete_to_trash）
 *  - to_trash：把现存路径移入回收站（撤销 复制/新建/压缩/解压/写文件/恢复）
 *  - rename_back：改名回去
 *  - move_back：移回原父目录
 */
class UndoManager(private val appContext: Context) {

    // ---------------- 逆操作序列化（纯函数实现见 [InverseOps]） ----------------

    fun inverseRestoreTrash(originalPaths: List<String>): String =
        InverseOps.restoreTrash(originalPaths)

    fun inverseToTrash(paths: List<String>): String =
        InverseOps.toTrash(paths)

    fun inverseRenameBack(currentPath: String, originalPath: String): String =
        InverseOps.renameBack(currentPath, originalPath)

    fun inverseMoveBack(pairs: List<Pair<String, String>>): String =
        InverseOps.moveBack(pairs)

    /** 批量重命名的逆操作：(当前名路径, 原名路径) 对列表。 */
    fun inverseRenamePairs(pairs: List<Pair<String, String>>): String =
        InverseOps.renamePairs(pairs)

    // ---------------- 日志 ----------------

    suspend fun record(
        agentRepository: AgentRepository,
        userInstruction: String,
        toolName: String,
        paramsSummary: String,
        resultSummary: String,
        successCount: Int,
        failCount: Int,
        skipCount: Int,
        undoable: Boolean,
        inverseJson: String,
        affectedDir: String
    ): Long = agentRepository.insertOpLog(
        AgentOpLogEntity(
            ts = System.currentTimeMillis(),
            userInstruction = userInstruction.take(500),
            toolName = toolName,
            paramsSummary = paramsSummary.take(2000),
            resultSummary = resultSummary.take(2000),
            successCount = successCount,
            failCount = failCount,
            skipCount = skipCount,
            undoable = undoable && inverseJson.isNotEmpty(),
            undoState = if (undoable && inverseJson.isNotEmpty()) STATE_AVAILABLE else STATE_NONE,
            inverseJson = inverseJson,
            affectedDir = affectedDir
        )
    )

    /** 撤销最近一条可撤销记录。 */
    suspend fun undoLatest(
        repository: Repository,
        agentRepository: AgentRepository
    ): UndoReport? = withContext(Dispatchers.IO) {
        val log = agentRepository.latestUndoable() ?: return@withContext null
        val report = try {
            executeInverse(log.inverseJson, repository)
        } catch (t: Throwable) {
            UndoReport(0, 1, listOf("撤销异常：${t.message}"), log.affectedDir)
        }
        val state = if (report.failed == 0) STATE_UNDONE else STATE_FAILED
        agentRepository.updateUndoState(
            log.id,
            state,
            log.resultSummary + " | " + report.details.joinToString(";").take(500)
        )
        report
    }

    /** 按逆操作 JSON 回放（供单测直接调用）。 */
    suspend fun executeInverse(json: String, repository: Repository): UndoReport =
        withContext(Dispatchers.IO) {
            val o = JSONObject(json)
            val details = ArrayList<String>()
            var undone = 0
            var failed = 0
            val affected = StringBuilder()

            fun ok(p: String) {
                undone++
                if (affected.isEmpty()) affected.append(p.substringBeforeLast('/').ifEmpty { "/" })
            }
            fun fail(p: String, why: String?) {
                failed++
                details.add("$p：${why ?: "失败"}")
            }

            when (o.optString("type")) {
                "restore_trash" -> {
                    val trashDao = FreeFileDatabase.get(appContext).trashDao()
                    val paths = o.getJSONArray("original_paths")
                    for (i in 0 until paths.length()) {
                        val original = paths.optString(i)
                        try {
                            val item = trashDao.findByOriginalPath(original)
                            if (item == null) {
                                fail(original, "回收站记录不存在"); continue
                            }
                            if (repository.restore(item)) ok(original) else fail(original, "恢复失败")
                        } catch (t: Throwable) {
                            fail(original, t.message)
                        }
                    }
                }
                "to_trash" -> {
                    val paths = o.getJSONArray("paths")
                    for (i in 0 until paths.length()) {
                        val p = paths.optString(i)
                        try {
                            val entry = EngineSelector.select(p).stat(p)
                            if (entry == null) {
                                // 目标已不存在，视为撤销达成
                                ok(p); continue
                            }
                            if (repository.moveToTrash(listOf(entry))) ok(p) else fail(p, "移入回收站失败")
                        } catch (t: Throwable) {
                            fail(p, t.message)
                        }
                    }
                }
                "rename_back" -> {
                    val from = o.optString("from")
                    val to = o.optString("to")
                    try {
                        val engine = EngineSelector.select(from)
                        if (engine.rename(from, to)) ok(to) else fail(from, "改名失败")
                    } catch (t: Throwable) {
                        fail(from, t.message)
                    }
                }
                "rename_pairs" -> {
                    val pairs = o.getJSONArray("pairs")
                    for (i in 0 until pairs.length()) {
                        val p = pairs.optJSONObject(i) ?: continue
                        val from = p.optString("from")
                        val to = p.optString("to")
                        try {
                            val engine = EngineSelector.select(from)
                            if (engine.rename(from, to)) ok(to) else fail(from, "改名失败")
                        } catch (t: Throwable) {
                            fail(from, t.message)
                        }
                    }
                }
                "move_back" -> {
                    val pairs = o.getJSONArray("pairs")
                    for (i in 0 until pairs.length()) {
                        val po = pairs.getJSONObject(i)
                        val from = po.optString("from")
                        val toDir = po.optString("to_dir")
                        try {
                            val engine = EngineSelector.select(from)
                            val entry = engine.stat(from)
                            if (entry == null) {
                                fail(from, "源不存在"); continue
                            }
                            engine.mkdirs(toDir)
                            val dest = joinPath(toDir, entry.name)
                            val renamed = try {
                                engine.rename(from, dest)
                            } catch (_: Throwable) {
                                false
                            }
                            if (renamed) {
                                ok(dest)
                            } else {
                                // 跨卷 rename 失败：复制后删除
                                com.freefile.operations.FileOperations.move(
                                    listOf(entry), toDir,
                                    onProgress = { _, _, _, _, _ -> }
                                )
                                ok(dest)
                            }
                        } catch (t: Throwable) {
                            fail(from, t.message)
                        }
                    }
                }
                else -> {
                    fail(o.optString("type"), "未知逆操作类型")
                }
            }
            UndoReport(undone, failed, details, affected.toString())
        }

    companion object {
        const val STATE_AVAILABLE = "available"
        const val STATE_UNDONE = "undone"
        const val STATE_FAILED = "failed"
        const val STATE_NONE = "none"
    }
}

/**
 * 逆操作 JSON 序列化的纯函数实现（不依赖 Android，可直接单元测试）。
 */
object InverseOps {

    fun restoreTrash(originalPaths: List<String>): String =
        JSONObject().put("type", "restore_trash")
            .put("original_paths", JSONArray(originalPaths)).toString()

    fun toTrash(paths: List<String>): String =
        JSONObject().put("type", "to_trash")
            .put("paths", JSONArray(paths)).toString()

    fun renameBack(currentPath: String, originalPath: String): String =
        JSONObject().put("type", "rename_back")
            .put("from", currentPath).put("to", originalPath).toString()

    fun moveBack(pairs: List<Pair<String, String>>): String {
        val arr = JSONArray()
        pairs.forEach { (from, originalParent) ->
            arr.put(JSONObject().put("from", from).put("to_dir", originalParent))
        }
        return JSONObject().put("type", "move_back").put("pairs", arr).toString()
    }

    fun renamePairs(pairs: List<Pair<String, String>>): String {
        val arr = JSONArray()
        pairs.forEach { (current, original) ->
            arr.put(JSONObject().put("from", current).put("to", original))
        }
        return JSONObject().put("type", "rename_pairs").put("pairs", arr).toString()
    }
}
