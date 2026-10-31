package com.freefile.tools.ai

import com.freefile.tools.ai.model.WritePreview
import com.freefile.tools.ai.security.ConfirmationGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 确认闸门：令牌一次性、批准携带用户修改、取消恢复 null、待确认状态可观察。 */
class ConfirmationGateTest {

    private fun preview(token: String) = WritePreview(
        token = token,
        toolName = "move",
        title = "移动",
        items = emptyList(),
        destination = "/storage/emulated/0/Target",
        destinationEditable = true,
        destinationLabel = "目标目录",
        conflicts = listOf("a.pdf")
    )

    @Test
    fun approve_returns_user_decision_and_clears_pending() = runBlocking {
        val gate = ConfirmationGate()
        val p = preview("tok-1")
        val job = launch {
            val decision = withTimeout(5_000) { gate.request(p) }
            assertNotNull(decision)
            assertEquals(listOf("/sdcard/a.pdf"), decision!!.selectedPaths)
            assertEquals("/storage/emulated/0/Edited", decision.destination)
            assertEquals(WritePreview.CONFLICT_SKIP, decision.conflictPolicy)
        }
        // 等待待确认状态暴露给 UI
        val shown = withTimeout(5_000) { gate.pending.first { it != null } }
        assertEquals("tok-1", shown!!.token)

        gate.approve(
            token = "tok-1",
            selectedPaths = listOf("/sdcard/a.pdf"),
            destination = "/storage/emulated/0/Edited",
            conflictPolicy = WritePreview.CONFLICT_SKIP,
            overwriteAcknowledged = false
        )
        job.join()
        assertNull(gate.pending.value)
    }

    @Test
    fun cancel_returns_null_and_wrong_token_is_ignored() = runBlocking {
        val gate = ConfirmationGate()
        val p = preview("tok-2")
        val job = launch {
            val decision = withTimeout(5_000) { gate.request(p) }
            assertNull(decision)
        }
        withTimeout(5_000) { gate.pending.first { it != null } }

        // 错误令牌不能完成确认
        gate.approve("wrong", emptyList(), "/x", WritePreview.CONFLICT_RENAME, false)
        assertNotNull(gate.pending.value)

        gate.cancel("tok-2")
        job.join()
        assertNull(gate.pending.value)
        assertFalse(job.isActive)
    }

    @Test
    fun cancel_current_unblocks_waiter() = runBlocking {
        val gate = ConfirmationGate()
        val p = preview("tok-3")
        val job = launch {
            assertNull(withTimeout(5_000) { gate.request(p) })
        }
        withTimeout(5_000) { gate.pending.first { it != null } }
        delay(50)
        gate.cancelCurrent()
        job.join()
        assertNull(gate.pending.value)
    }
}
