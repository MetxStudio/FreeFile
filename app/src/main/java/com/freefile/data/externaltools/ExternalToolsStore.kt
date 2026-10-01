package com.freefile.data.externaltools

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.freefile.ui.tools.ToolInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * /FreeFile/Data/external-tools.json 的统一存储入口。
 * API 30+ 优先使用用户授权的 SAF tree；仅在用户明确授予所有文件访问时才使用 File API。
 */
interface StorageRepository {
	fun read(): String?
	fun write(content: String)
}

class ExternalToolsStore(private val context: Context) : StorageRepository {
	private val resolver = context.contentResolver
	private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

	override fun read(): String? = try {
		withBackend { input ->
			input.bufferedReader(Charsets.UTF_8).use { it.readText() }
		}.takeIf { it.isNotBlank() }
	} catch (_: java.io.FileNotFoundException) {
		null
	}

	override fun write(content: String) {
		writeUsingBackend(content)
	}

	/** 读取旧配置、按包名/id 更新条目，再原子写回。 */
	fun upsert(tool: ToolInfo) {
		val root = try {
			read()?.let(::JSONObject) ?: JSONObject()
		} catch (e: Exception) {
			throw IOException("external-tools.json 格式错误", e)
		}
		val old = root.optJSONArray("tools") ?: JSONArray()
		val result = JSONArray()
		var replaced = false
		for (i in 0 until old.length()) {
			val item = old.optJSONObject(i) ?: continue
			val same = tool.packageName.isNotBlank() &&
				item.optString("packageName") == tool.packageName ||
				tool.packageName.isBlank() && item.optString("id") == tool.id
			if (same) {
				result.put(toJson(tool))
				replaced = true
			} else {
				result.put(item)
			}
		}
		if (!replaced) result.put(toJson(tool))
		root.put("tools", result)
		write(root.toString(2))
	}

	fun persistedTreeUri(): Uri? = preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)

	fun saveTreeUri(uri: Uri) {
		preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
	}

	fun clearTreeUri() {
		preferences.edit().remove(KEY_TREE_URI).apply()
	}

	fun canUseFileBackend(): Boolean {
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) return true
		return Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ||
			(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
	}

	private fun <T> withBackend(block: (java.io.InputStream) -> T): T {
		val tree = persistedTreeUri()
		if (tree != null) {
			try {
				val file = documentFile(tree)
				val input = file?.takeIf { it.isFile }?.let { resolver.openInputStream(it.uri) }
				if (input != null) return input.use(block)
			} catch (_: Exception) {
				// SAF 授权被撤销时清理失效 URI，再尝试明确授权的 File 后端。
				clearTreeUri()
			}
		}
		val file = fileBackend()
			?: throw IOException("未授权 /FreeFile/Data，请先选择目录或授予所有文件访问")
		if (!file.exists()) throw java.io.FileNotFoundException(file.absolutePath)
		return block(file.inputStream())
	}

	private fun writeUsingBackend(content: String) {
		val tree = persistedTreeUri()
		if (tree != null) {
			try {
				val directory = DocumentFile.fromTreeUri(context, tree)
					?: throw IOException("SAF 目录不可用")
				if (!directory.canWrite()) throw IOException("SAF 目录不可写")
				val temp = directory.findFile(FILE_NAME + ".tmp")
					?: directory.createFile("application/json", FILE_NAME + ".tmp")
					?: throw IOException("无法创建临时配置文件")
				resolver.openOutputStream(temp.uri, "wt")?.use {
					it.write(content.toByteArray(Charsets.UTF_8))
					it.flush()
				} ?: throw IOException("无法写入临时配置文件")
				val verify = resolver.openInputStream(temp.uri)?.use {
					it.bufferedReader(Charsets.UTF_8).readText()
				}
				if (verify != content) throw IOException("配置文件校验失败")
				val target = directory.findFile(FILE_NAME)
				if (target != null && !target.delete()) throw IOException("无法替换配置文件")
				if (!temp.renameTo(FILE_NAME)) throw IOException("无法提交配置文件")
				return
			} catch (e: Exception) {
				clearTreeUri()
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
					!Environment.isExternalStorageManager()
				) throw IOException("SAF 授权已失效，请重新选择 /FreeFile/Data", e)
			}
		}
		val target = fileBackend() ?: throw IOException("未授权 /FreeFile/Data")
		val directory = target.parentFile ?: throw IOException("目标目录无效")
		directory.mkdirs()
		val temp = File(directory, FILE_NAME + ".tmp")
		temp.writeText(content, Charsets.UTF_8)
		if (temp.readText(Charsets.UTF_8) != content) throw IOException("配置文件校验失败")
		if (target.exists() && !target.delete()) throw IOException("无法替换配置文件")
		if (!temp.renameTo(target)) throw IOException("无法提交配置文件")
	}

	private fun fileBackend(): File? {
		if (!canUseFileBackend()) return null
		return File(
			Environment.getExternalStorageDirectory(),
			"$ROOT_DIRECTORY/$DATA_DIRECTORY/$FILE_NAME"
		)
	}

	private fun documentFile(treeUri: Uri): DocumentFile? =
		DocumentFile.fromTreeUri(context, treeUri)?.findFile(FILE_NAME)

	private fun toJson(tool: ToolInfo): JSONObject = JSONObject().apply {
		put("id", tool.id)
		put("name", tool.name)
		put("apkFile", tool.apkFile)
		put("packageName", tool.packageName)
		put("launchActivity", tool.launchActivity)
		put("version", tool.version)
		put("description", tool.description)
		put("intentAction", tool.intentAction)
		put("intentCategory", tool.intentCategory)
		put("intentData", tool.intentData)
		put("intentExtra", tool.intentExtra)
		put("intentFlags", tool.intentFlags)
	}

	companion object {
		private const val PREFERENCES = "external_tools_storage"
		private const val KEY_TREE_URI = "tree_uri"
		const val ROOT_DIRECTORY = "FreeFile"
		const val DATA_DIRECTORY = "Data"
		const val FILE_NAME = "external-tools.json"
	}
}
