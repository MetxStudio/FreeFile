package com.freefile.tools.ai.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.freefile.tools.ai.AgentConstants

// ==================== 实体 ====================

/** Agent 会话。 */
@Entity(tableName = "ai_conversations")
data class AgentConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 会话消息（OpenAI 角色：system/user/assistant/tool）。
 * assistant 发起工具调用时 [toolCallsJson] 保存完整 tool_calls 数组；
 * role=tool 时 [toolCallId] 为对应的调用 id，[content] 为工具结果 JSON，
 * [toolName]/[callStatus] 供 UI 渲染工具卡片。
 */
@Entity(tableName = "ai_messages")
data class AgentMessageEntity(
    @PrimaryKey val id: String,
    val convId: String,
    val role: String,
    val content: String,
    val toolCallsJson: String = "",
    val toolCallId: String = "",
    val toolName: String = "",
    val callStatus: String = "",
    val sortKey: Long,
    val createdAt: Long
)

/** 写操作审计日志（同时承载撤销所需的逆操作 JSON）。 */
@Entity(tableName = "ai_op_logs")
data class AgentOpLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val userInstruction: String,
    val toolName: String,
    val paramsSummary: String,
    val resultSummary: String,
    val successCount: Int,
    val failCount: Int,
    val skipCount: Int,
    val undoable: Boolean,
    /** available / undone / failed / none */
    val undoState: String,
    /** 逆操作序列化 JSON，供 UndoManager 跨进程恢复执行。 */
    val inverseJson: String,
    /** 受影响目录（写成功后通知 FileChangeBus / UI 定位用）。 */
    val affectedDir: String
)

// ==================== DAO ====================

@Dao
interface AgentConversationDao {
    @Query("SELECT * FROM ai_conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): AgentConversationEntity?

    @Query("SELECT * FROM ai_conversations ORDER BY updatedAt DESC")
    suspend fun all(): List<AgentConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentConversationEntity)

    @Query("UPDATE ai_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("SELECT * FROM ai_conversations WHERE id = :id")
    suspend fun byId(id: String): AgentConversationEntity?

    @Query("DELETE FROM ai_conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AgentMessageDao {
    @Query("SELECT * FROM ai_messages WHERE convId = :convId ORDER BY sortKey")
    suspend fun byConversation(convId: String): List<AgentMessageEntity>

    @Query("SELECT COALESCE(MAX(sortKey), 0) FROM ai_messages WHERE convId = :convId")
    suspend fun maxSortKey(convId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AgentMessageEntity>)

    @Query("UPDATE ai_messages SET content = :content, callStatus = :status WHERE id = :id")
    suspend fun updateToolResult(id: String, content: String, status: String)

    @Query("DELETE FROM ai_messages WHERE convId = :convId")
    suspend fun clearConversation(convId: String)
}

@Dao
interface AgentOpLogDao {
    @Query("SELECT * FROM ai_op_logs ORDER BY ts DESC LIMIT 500")
    suspend fun recent(): List<AgentOpLogEntity>

    @Query("SELECT * FROM ai_op_logs WHERE undoState = 'available' ORDER BY ts DESC LIMIT 1")
    suspend fun latestUndoable(): AgentOpLogEntity?

    @Insert
    suspend fun insert(entity: AgentOpLogEntity): Long

    @Query("UPDATE ai_op_logs SET undoState = :state, resultSummary = :summary WHERE id = :id")
    suspend fun updateUndo(id: Long, state: String, summary: String)

    @Query("DELETE FROM ai_op_logs")
    suspend fun clear()
}

// ==================== Database ====================

/**
 * Agent 独立数据库（freefile_agent.db v1）。
 * 刻意与现有 freefile.db 隔离，避免版本升级触发 destructive migration
 * 清空用户书签/回收站映射/搜索索引。
 */
@Database(
    entities = [
        AgentConversationEntity::class,
        AgentMessageEntity::class,
        AgentOpLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun conversationDao(): AgentConversationDao
    abstract fun messageDao(): AgentMessageDao
    abstract fun opLogDao(): AgentOpLogDao

    companion object {
        @Volatile
        private var instance: AgentDatabase? = null

        fun get(context: Context): AgentDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    AgentConstants.AGENT_DB_NAME
                ).build().also { instance = it }
            }
    }
}
