package com.freefile.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.freefile.vfs.ArchiveEngine
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.remote.RemoteEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件打开工具（需求：打开交互三选项 + 解压复用 Intent 逻辑）。
 *
 * 提供三种打开方式：
 *  1. [shareFile]  分享文件（系统分享 ACTION_SEND）
 *  2. [openWithMime] 用 MIME 及系统自带 App Chooser 打开（ACTION_VIEW + chooser）
 *  3. [openBuiltin] 使用文件管理器内置功能打开（按类型分发：文本→编辑器、压缩包→解压、其他→内置 Intent）
 *
 * 压缩包内文件会先提取到缓存目录再以 Intent 传递（复用统一的 Intent 构建逻辑）。
 */
object FileOpener {

    /** 可由内置文本编辑器打开的扩展名。 */
    private val TEXT_EXT = setOf(
        "txt", "md", "log", "json", "xml", "csv", "ini", "conf", "properties",
        "java", "kt", "c", "cpp", "h", "hpp", "py", "lua", "sh", "bat", "html", "htm",
        "css", "js", "ts", "yml", "yaml", "gradle", "smali"
    )

    fun isTextFile(entry: VfsEntry): Boolean =
        !entry.isDirectory && entry.extension in TEXT_EXT

    /**
     * 准备可被 Intent 传递的本地文件（压缩包内文件先提取到缓存）。
     * 返回 (File, mime) 对；失败返回 null。
     * 这是「第4点传递 Intent 的逻辑」核心：所有打开方式共用此准备流程。
     */
    suspend fun prepareSharableFile(
        context: Context,
        entry: VfsEntry,
        toast: (String) -> Unit
    ): Pair<File, String>? = withContext(Dispatchers.IO) {
        // 压缩包内文件 / 网络位置文件 → 提取/下载到缓存目录
        val needCache = ArchiveEngine.isArchivePath(entry.path) ||
            RemoteEngine.isRemotePath(entry.path)
        val targetFile: File = if (needCache) {
            try {
                val dir = File(context.cacheDir, "open_preview").apply { mkdirs() }
                val f = File(dir, entry.name)
                EngineSelector.select(entry.path).openInputStream(entry.path).use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
                f
            } catch (e: Exception) {
                toast(context.getString(
                    com.freefile.R.string.msg_open_failed, e.message ?: ""
                ))
                return@withContext null
            }
        } else {
            val f = File(entry.path)
            if (!f.exists()) {
                toast(context.getString(com.freefile.R.string.msg_open_non_local))
                return@withContext null
            }
            f
        }
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(entry.extension) ?: "application/octet-stream"
        targetFile to mime
    }

    /** 构建 content Uri（通过 FileProvider）并授权。 */
    private fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** ① 分享文件：调用系统分享。 */
    suspend fun shareFile(context: Context, entry: VfsEntry, toast: (String) -> Unit) {
        shareFiles(context, listOf(entry), toast)
    }

    /** ① 分享多个文件：调用系统分享（ACTION_SEND_MULTIPLE）。 */
    suspend fun shareFiles(context: Context, entries: List<VfsEntry>, toast: (String) -> Unit) {
        if (entries.isEmpty()) return
        val pairs = entries.mapNotNull { prepareSharableFile(context, it, toast) }
        if (pairs.isEmpty()) return
        val uris = ArrayList(pairs.map { shareUri(context, it.first) })
        val mime = pairs.first().second
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, entries.first().name).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            toast(context.getString(com.freefile.R.string.msg_no_app))
        }
    }

    /** ② 用 MIME 及系统自带 App Chooser 打开。 */
    suspend fun openWithMime(context: Context, entry: VfsEntry, toast: (String) -> Unit) {
        val (file, mime) = prepareSharableFile(context, entry, toast) ?: return
        val uri = shareUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, entry.name).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            toast(context.getString(com.freefile.R.string.msg_no_app))
        }
    }

    /**
     * ③ 使用文件管理器内置功能打开（按类型分发，传入对应 Intent）。
     *  - 压缩包 → 返回 [BuiltinResult.Extract]，由调用方触发解压流程（复用 Intent 逻辑）
     *  - 文本类 → 返回 [BuiltinResult.EditText]，由调用方打开内置文本编辑器
     *  - 其他本地文件 → 直接用 ACTION_VIEW 内置 Intent 打开
     *
     * 返回 [BuiltinResult] 以便调用方在 Compose 层路由到解压/编辑器等需要 UI 的流程。
     */
    suspend fun openBuiltin(
        context: Context,
        entry: VfsEntry,
        toast: (String) -> Unit
    ): BuiltinResult = withContext(Dispatchers.IO) {
        // 压缩包：内置打开 = 解压（复用 Intent 逻辑）
        if (!entry.isDirectory && ArchiveEngine.isArchiveFile(entry.name)) {
            return@withContext BuiltinResult.Extract(entry.path)
        }
        // 文本类：内置打开 = 文本编辑器
        if (isTextFile(entry)) {
            return@withContext BuiltinResult.EditText(entry.path)
        }
        // 其他：尝试用内置 ACTION_VIEW Intent 打开
        val (file, mime) = prepareSharableFile(context, entry, toast)
            ?: return@withContext BuiltinResult.Failed
        val uri = shareUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            BuiltinResult.Done
        } catch (e: Exception) {
            toast(context.getString(com.freefile.R.string.msg_no_app))
            BuiltinResult.Failed
        }
    }

    /** 内置打开结果。 */
    sealed class BuiltinResult {
        /** 需要解压：携带压缩包路径。 */
        data class Extract(val archivePath: String) : BuiltinResult()
        /** 需要打开文本编辑器：携带文件路径。 */
        data class EditText(val filePath: String) : BuiltinResult()
        /** 已直接用 Intent 打开。 */
        object Done : BuiltinResult()
        /** 打开失败。 */
        object Failed : BuiltinResult()
    }
}
