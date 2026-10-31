package com.freefile.tools.ai

import com.freefile.tools.ai.tool.ToolArgs.bool
import com.freefile.tools.ai.tool.ToolArgs.int
import com.freefile.tools.ai.tool.ToolArgs.long
import com.freefile.tools.ai.tool.ToolArgs.str
import com.freefile.tools.ai.tool.ToolArgs.strList
import com.freefile.tools.ai.tool.ToolResults
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型参数解析的安全默认值 + 列表截断（100 条上限，防止撑爆上下文）。 */
class ToolArgsResultsTest {

    @Test
    fun args_fall_back_to_defaults() {
        val o = JSONObject()
        assertEquals("", o.str("missing"))
        assertEquals("fallback", o.str("missing", "fallback"))
        assertEquals(7, o.int("missing", 7))
        assertEquals(99L, o.long("missing", 99L))
        assertTrue(!o.bool("missing"))

        val typed = JSONObject()
            .put("name", "  report.pdf ")
            .put("n", "not-a-number")
        assertEquals("report.pdf", typed.str("name"))
        assertEquals(0, typed.int("n"))
    }

    @Test
    fun str_list_accepts_array_and_csv() {
        val arr = JSONObject().put("paths", JSONArray(listOf("/a", "/b")))
        assertEquals(listOf("/a", "/b"), arr.strList("paths"))

        val csv = JSONObject().put("paths", "/a , /b,/c")
        assertEquals(listOf("/a", "/b", "/c"), csv.strList("paths"))

        assertEquals(emptyList<String>(), JSONObject().strList("none"))
    }

    @Test
    fun base_list_caps_at_100() {
        val big = (1..150).map { JSONObject().put("path", "/f/$it") }
        val result = ToolResults.baseList(big, big.size)
        assertEquals(150, result.getInt("total"))
        assertEquals(100, result.getInt("returned"))
        assertTrue(result.getBoolean("has_more"))
        assertEquals(100, result.getJSONArray("items").length())

        val small = ToolResults.baseList(listOf(JSONObject().put("path", "/x")), 1)
        assertEquals(1, small.getInt("returned"))
        assertTrue(!small.getBoolean("has_more"))
    }
}
