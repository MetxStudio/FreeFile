package com.freefile.tools.ai.security

import com.freefile.tools.ai.model.Approval
import com.freefile.tools.ai.model.WritePreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 写操作确认闸门：同一时刻只允许一个待确认写操作（锁定确认流程，不被闲聊打断）。
 *
 * 流程：工具构造 [WritePreview] → [request] 挂起等待 → UI 渲染确认卡 →
 * approve/cancel 恢复执行协程；令牌一次性、与预览绑定。
 */
class ConfirmationGate {

    private data class Held(
        val preview: WritePreview,
        val deferred: CompletableDeferred<Approval?>
    )

    private val _pending = MutableStateFlow<WritePreview?>(null)
    val pending: StateFlow<WritePreview?> = _pending.asStateFlow()

    private val held = ConcurrentHashMap<String, Held>()

    /**
     * 「始终允许」判定回调（Orchestrator 注入）：
     * 返回 true 时 [request] 不再弹卡，直接以预览原参数自动批准。
     * 注入方必须保证破坏性操作（destructive）不在此列。
     */
    @Volatile
    var autoAllow: ((WritePreview) -> Boolean)? = null

    fun newToken(): String = UUID.randomUUID().toString()

    /** 挂起当前工具调用，等待用户决策；返回 null 表示取消。 */
    suspend fun request(preview: WritePreview): Approval? {
        val allow = autoAllow?.invoke(preview) == true
        if (allow) {
            return Approval(
                token = preview.token,
                approved = true,
                selectedPaths = preview.items.filter { it.selected }.map { it.path },
                destination = preview.destination,
                conflictPolicy = preview.conflictPolicy,
                overwriteAcknowledged = false
            )
        }
        val deferred = CompletableDeferred<Approval?>()
        held[preview.token] = Held(preview, deferred)
        _pending.value = preview
        return try {
            deferred.await()
        } finally {
            held.remove(preview.token)
            if (_pending.value?.token == preview.token) _pending.value = null
        }
    }

    fun approve(
        token: String,
        selectedPaths: List<String>,
        destination: String,
        conflictPolicy: String,
        overwriteAcknowledged: Boolean
    ) {
        val h = held[token] ?: return
        if (h.deferred.isCompleted) return
        h.deferred.complete(
            Approval(
                token = token,
                approved = true,
                selectedPaths = selectedPaths,
                destination = destination.trim(),
                conflictPolicy = conflictPolicy,
                overwriteAcknowledged = overwriteAcknowledged
            )
        )
    }

    fun cancel(token: String) {
        val h = held[token] ?: return
        if (h.deferred.isCompleted) return
        h.deferred.complete(null)
    }

    /** 取消当前待确认项（用户在别处触发整体停止时）。 */
    fun cancelCurrent() {
        _pending.value?.token?.let { cancel(it) }
    }
}
