package com.freefile.tools.ai.tool

import com.freefile.vfs.VfsEntry
import org.json.JSONArray
import org.json.JSONObject

/** 模型参数解析的安全小工具：缺省/类型错误均回退默认值，不抛异常。 */
object ToolArgs {

    fun JSONObject.str(key: String, default: String = ""): String =
        if (has(key) && !isNull(key)) optString(key, default).trim() else default

    fun JSONObject.strOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().ifEmpty { null } else null

    fun JSONObject.long(key: String, default: Long = 0L): Long =
        if (has(key) && !isNull(key)) optLong(key, default) else default

    fun JSONObject.int(key: String, default: Int = 0): Int =
        if (has(key) && !isNull(key)) optInt(key, default) else default

    fun JSONObject.bool(key: String, default: Boolean = false): Boolean =
        if (has(key) && !isNull(key)) optBoolean(key, default) else default

    fun JSONObject.strList(key: String): List<String> {
        val v = opt(key) ?: return emptyList()
        return when (v) {
            is JSONArray -> (0 until v.length()).mapNotNull {
                v.optString(it).trim().ifEmpty { null }
            }
            is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }
}

/** VfsEntry → 模型可见 JSON（文件事实的标准结构）。 */
fun VfsEntry.toJson(): JSONObject = JSONObject()
    .put("path", path)
    .put("name", name)
    .put("is_directory", isDirectory)
    .put("size", size)
    .put("last_modified", lastModified)
    .put("extension", extension)
    .put("can_read", canRead)
    .put("can_write", canWrite)
    .put("is_hidden", isHidden)
