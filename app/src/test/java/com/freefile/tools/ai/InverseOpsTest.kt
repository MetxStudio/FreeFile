package com.freefile.tools.ai

import com.freefile.tools.ai.security.InverseOps
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 撤销逆操作 JSON 结构测试：保证 UndoManager 可跨进程回放。 */
class InverseOpsTest {

    @Test
    fun to_trash_round_trip() {
        val o = JSONObject(InverseOps.toTrash(listOf("/sdcard/a.zip", "/sdcard/b.txt")))
        assertEquals("to_trash", o.getString("type"))
        val arr = o.getJSONArray("paths")
        assertEquals(2, arr.length())
        assertEquals("/sdcard/a.zip", arr.getString(0))
        assertEquals("/sdcard/b.txt", arr.getString(1))
    }

    @Test
    fun restore_trash_round_trip() {
        val o = JSONObject(InverseOps.restoreTrash(listOf("/sdcard/del.txt")))
        assertEquals("restore_trash", o.getString("type"))
        assertEquals("/sdcard/del.txt", o.getJSONArray("original_paths").getString(0))
    }

    @Test
    fun rename_back_round_trip() {
        val o = JSONObject(InverseOps.renameBack("/d/new.txt", "/d/old.txt"))
        assertEquals("rename_back", o.getString("type"))
        assertEquals("/d/new.txt", o.getString("from"))
        assertEquals("/d/old.txt", o.getString("to"))
    }

    @Test
    fun move_back_round_trip() {
        val o = JSONObject(
            InverseOps.moveBack(
                listOf(
                    "/target/a.pdf" to "/source",
                    "/target/sub/b.png" to "/source/sub"
                )
            )
        )
        assertEquals("move_back", o.getString("type"))
        val pairs = o.getJSONArray("pairs")
        assertEquals(2, pairs.length())
        assertEquals("/target/a.pdf", pairs.getJSONObject(0).getString("from"))
        assertEquals("/source", pairs.getJSONObject(0).getString("to_dir"))
        assertEquals("/source/sub", pairs.getJSONObject(1).getString("to_dir"))
    }
}
