package com.freefile.tools.ai.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Agent 会话 / 消息 / 操作日志的数据访问层。
 * 当前会话消息以内存 StateFlow 承载（旋转屏直接存活），落库用于进程重建；
 * 会话列表独立 StateFlow，支持多会话切换 / 重命名 / 删除。
 */
class AgentRepository(context: Context) {

    private val db = AgentDatabase.get(context)
    private val convDao = db.conversationDao()
    private val msgDao = db.messageDao()
    private val logDao = db.opLogDao()

    private val _messages = MutableStateFlow<List<AgentMessageEntity>>(emptyList())
    val messages: Flow<List<AgentMessageEntity>> = _messages

    private val _conversations = MutableStateFlow<List<AgentConversationEntity>>(emptyList())
    val conversations: Flow<List<AgentConversationEntity>> = _conversations

    var currentConversationId: String = ""
        private set

    /** 启动时恢复最近会话；没有则新建。 */
    suspend fun ensureConversation(): String {
        if (currentConversationId.isNotEmpty()) return currentConversationId
        val latest = convDao.latest()
        currentConversationId = latest?.id ?: UUID.randomUUID().toString().also { id ->
            convDao.upsert(
                AgentConversationEntity(
                    id = id,
                    title = "New chat",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        _messages.value = msgDao.byConversation(currentConversationId)
        refreshConversations()
        return currentConversationId
    }

    suspend fun newConversation(): String {
        stopIfActive()
        currentConversationId = UUID.randomUUID().toString()
        convDao.upsert(
            AgentConversationEntity(
                id = currentConversationId,
                title = "New chat",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        _messages.value = emptyList()
        refreshConversations()
        return currentConversationId
    }

    /** 切换到已有会话（加载其完整消息历史，实现独立语境）。 */
    suspend fun switchConversation(convId: String): Boolean {
        if (convId == currentConversationId) return true
        stopIfActive()
        val conv = convDao.byId(convId) ?: return false
        currentConversationId = conv.id
        _messages.value = msgDao.byConversation(convId)
        refreshConversations()
        return true
    }

    /** 重命名当前会话（保留 createdAt，仅更新标题并置顶）。 */
    suspend fun renameConversation(title: String) {
        val id = currentConversationId.ifEmpty { return }
        val t = title.trim().take(40)
        if (t.isEmpty()) return
        convDao.rename(id, t, System.currentTimeMillis())
        refreshConversations()
    }

    suspend fun renameConversationById(convId: String, title: String) {
        val t = title.trim().take(40)
        if (t.isEmpty()) return
        convDao.rename(convId, t, System.currentTimeMillis())
        refreshConversations()
    }

    /** 删除指定会话；删除当前会话时自动切到最近一个或新建。 */
    suspend fun deleteConversation(convId: String) {
        msgDao.clearConversation(convId)
        convDao.delete(convId)
        if (convId == currentConversationId) {
            currentConversationId = ""
            ensureConversation()
        } else {
            refreshConversations()
        }
    }

    /** 首条用户消息后自动生成会话标题。 */
    suspend fun autoTitleIfNeeded(firstUserText: String) {
        val id = currentConversationId.ifEmpty { return }
        val conv = convDao.byId(id) ?: return
        if (conv.title != "New chat") return
        val t = firstUserText.replace('\n', ' ').trim().take(20)
        if (t.isNotEmpty()) {
            convDao.rename(id, t, System.currentTimeMillis())
            refreshConversations()
        }
    }

    private suspend fun refreshConversations() {
        _conversations.value = convDao.all()
    }

    /** 会话操作的钩子：切走/删除前终止当前运行中的任务。 */
    private fun stopIfActive() {
        onStopRequested?.invoke()
    }

    /** 由 Orchestrator 注入：停止当前工具循环与确认等待。 */
    var onStopRequested: (() -> Unit)? = null

    suspend fun appendMessage(
        role: String,
        content: String,
        toolCallsJson: String = "",
        toolCallId: String = "",
        toolName: String = "",
        callStatus: String = ""
    ): AgentMessageEntity {
        val convId = ensureConversation()
        val sortKey = msgDao.maxSortKey(convId) + 1
        val entity = AgentMessageEntity(
            id = UUID.randomUUID().toString(),
            convId = convId,
            role = role,
            content = content,
            toolCallsJson = toolCallsJson,
            toolCallId = toolCallId,
            toolName = toolName,
            callStatus = callStatus,
            sortKey = sortKey,
            createdAt = System.currentTimeMillis()
        )
        msgDao.upsert(entity)
        _messages.value = _messages.value + entity
        // 会话置顶
        convDao.byId(convId)?.let { convDao.rename(convId, it.title, System.currentTimeMillis()) }
        return entity
    }

    /** 更新某条 tool 结果消息（用户确认/取消后把占位结果替换为真实结果）。 */
    suspend fun updateToolResult(messageId: String, content: String, status: String) {
        msgDao.updateToolResult(messageId, content, status)
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(content = content, callStatus = status) else it
        }
    }

    suspend fun clearCurrentConversation() {
        val convId = currentConversationId.ifEmpty { return }
        msgDao.clearConversation(convId)
        convDao.delete(convId)
        newConversation()
    }

    // ---------------- 操作日志 ----------------

    suspend fun insertOpLog(entity: AgentOpLogEntity): Long = logDao.insert(entity)

    suspend fun recentOpLogs(): List<AgentOpLogEntity> = logDao.recent()

    suspend fun latestUndoable(): AgentOpLogEntity? = logDao.latestUndoable()

    suspend fun updateUndoState(id: Long, state: String, summary: String) =
        logDao.updateUndo(id, state, summary)

    suspend fun clearLogs() = logDao.clear()

    /** 设置页"清除会话与操作日志"。 */
    suspend fun clearAllData() {
        logDao.clear()
        val convs = convDao.all()
        convs.forEach { msgDao.clearConversation(it.id); convDao.delete(it.id) }
        _messages.value = emptyList()
        currentConversationId = ""
        ensureConversation()
    }
}
