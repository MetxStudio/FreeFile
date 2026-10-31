package com.freefile.tools.web

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 标签持久化：SharedPreferences 保存 URL/标题/激活索引。
 * favicon 不持久化（体积大），重启后重新加载。
 */
class TabStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("web_viewer_tabs", Context.MODE_PRIVATE)

    data class Snapshot(
        val tabs: List<Pair<Long, String>>, // id to url
        val titles: Map<Long, String>,
        val activeIndex: Int
    )

    fun save(tabs: List<TabItem>, activeIndex: Int) {
        val arr = JSONArray()
        tabs.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("url", t.url)
                put("title", t.title)
            })
        }
        prefs.edit()
            .putString(KEY_TABS, arr.toString())
            .putInt(KEY_ACTIVE, activeIndex)
            .apply()
    }

    fun load(): Snapshot? {
        val json = prefs.getString(KEY_TABS, null) ?: return null
        return try {
            val arr = JSONArray(json)
            val tabs = mutableListOf<Pair<Long, String>>()
            val titles = mutableMapOf<Long, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                tabs.add(id to o.getString("url"))
                titles[id] = o.optString("title", "")
            }
            Snapshot(tabs, titles, prefs.getInt(KEY_ACTIVE, 0))
        } catch (_: Exception) {
            null
        }
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_TABS = "tabs"
        const val KEY_ACTIVE = "active"
    }
}
