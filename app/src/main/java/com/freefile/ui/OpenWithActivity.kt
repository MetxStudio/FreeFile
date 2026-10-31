package com.freefile.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.freefile.R
import com.freefile.operations.FileChangeBus
import com.freefile.operations.TransferManager
import com.freefile.security.FileCrypto
import com.freefile.tools.unzip.CompressionEngine
import com.freefile.tools.richeditor.RichEditorActivity
import com.freefile.tools.virtualbox.MainActivity as VirtualBoxMainActivity
import com.freefile.ui.dialogs.CompressDialog
import com.freefile.ui.dialogs.ConfirmExtractDialog
import com.freefile.ui.theme.FreeFileTheme
import com.freefile.util.FileOpener
import com.freefile.util.Format
import com.freefile.vfs.ArchiveEngine
import com.freefile.vfs.VfsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import java.io.File

/**
 * 接收到的文件/文件夹条目（可能来自外部 Intent 的 content:// URI 或内部路径）。
 */
private data class ReceivedFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val typeLabel: String
)

/**
 * 打开方式 Activity：单击文件时弹出，集中承载「打开方式」与「内置功能打开」。
 *
 * 支持的 Intent 来源：
 *  - 内部：[EXTRA_FILE_PATH] / [EXTRA_FILE_PATHS]（String 路径列表）
 *  - 外部：ACTION_VIEW(data) / ACTION_SEND(EXTRA_STREAM) /
 *          ACTION_SEND_MULTIPLE(EXTRA_STREAM) / ClipData
 *
 * 结果回传（兼容旧版「解压此压缩包」回传主页）：
 *  - [EXTRA_EXTRACT_PATH] : String
 *  - [EXTRA_DELETED_PATHS] : ArrayList<String>
 */
class OpenWithActivity : AppCompatActivity() {

    private var receivedFiles: List<ReceivedFile> = emptyList()

    // 加解密进度状态（驱动 Compose 覆盖层）；只能在主线程更新
    private var cryptoState by mutableStateOf<CryptoState>(CryptoState.Idle)

    // 必须在 onCreate 中（STARTED 之前）注册，否则 RESUMED 后注册会抛 IllegalStateException
    private val mediaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val deleted = res.data?.getStringArrayListExtra(
                com.freefile.ui.media.MediaViewerActivity.EXTRA_DELETED_PATHS
            )
            if (!deleted.isNullOrEmpty()) {
                setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_DELETED_PATHS, deleted))
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏与蓝色导航栏保持一致，浅色图标保证对比度
        window.statusBarColor = 0xFF1565C0.toInt()

        // ① 解析所有传入的 URI / 路径
        val uris = collectIncomingUris(intent)
        val pathsFromExtras = intent.getStringArrayListExtra(EXTRA_FILE_PATHS)
            ?: listOfNotNull(intent.getStringExtra(EXTRA_FILE_PATH))

        lifecycleScope.launch {
            val resolved = resolveReceivedFiles(uris, pathsFromExtras)
            if (resolved.isEmpty()) {
                Toast.makeText(
                    this@OpenWithActivity,
                    getString(R.string.open_with_no_files),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            receivedFiles = resolved
            render(resolved)
        }
    }

    /** 从 Intent 中收集所有可能的 URI。 */
    private fun collectIncomingUris(intent: Intent): List<Uri> {
        val result = mutableListOf<Uri>()
        // ACTION_SEND_MULTIPLE
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { result.addAll(it) }
        // ACTION_SEND / ACTION_VIEW 单 URI
        intent.data?.let { result.add(it) }
        // ClipData（API 16+，部分分享场景通过 clipData 传递）
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { result.add(it) }
            }
        }
        // 去重并保留顺序
        return result.distinct()
    }

    /** 将 URI 与内部路径统一解析为本地可访问的 ReceivedFile 列表。 */
    private suspend fun resolveReceivedFiles(
        uris: List<Uri>,
        paths: List<String>
    ): List<ReceivedFile> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ReceivedFile>()

        // 1) 内部传入的路径（file scheme 或绝对路径）
        for (p in paths) {
            val f = File(p)
            if (f.exists()) {
                list.add(f.toReceived())
            }
        }

        // 2) 外部 URI
        for (uri in uris) {
            // 取持久化权限（若发送方授予了 PERSISTABLE 权限）
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // 非持久化权限，忽略
            } catch (_: Exception) {
            }

            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val f = File(uri.path ?: continue)
                    if (f.exists()) list.add(f.toReceived())
                }
                "content" -> {
                    val resolved = resolveContentUri(uri)
                    if (resolved != null) list.add(resolved)
                }
                else -> {
                    // 未知 scheme，尝试当作路径处理
                    val f = File(uri.toString())
                    if (f.exists()) list.add(f.toReceived())
                }
            }
        }
        list
    }

    /** 把 content:// URI 解析为本地文件：优先取真实路径，失败则复制到缓存。 */
    private fun resolveContentUri(uri: Uri): ReceivedFile? {
        // 尝试直接从 path 获取（部分 file provider 的 path 段即为真实路径）
        val direct = uri.path?.let { File(it) }
        if (direct != null && direct.exists()) return direct.toReceived()

        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "file"
        // 复制到缓存目录，供压缩/解压/打开使用
        val cacheDir = File(cacheDir, "incoming").apply { mkdirs() }
        val target = File(cacheDir, name)
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            target.toReceived()
        } catch (e: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun File.toReceived(): ReceivedFile {
        val isDir = isDirectory
        val ext = extension.lowercase()
        val typeLabel = when {
            isDir -> getString(R.string.open_with_type_folder)
            ArchiveEngine.isArchiveFile(name) -> getString(R.string.open_with_type_archive)
            ext.isNotEmpty() -> ext.uppercase()
            else -> getString(R.string.open_with_type_file)
        }
        return ReceivedFile(
            path = absolutePath,
            name = name,
            isDirectory = isDir,
            size = if (isDir) 0L else length(),
            typeLabel = typeLabel
        )
    }

    private fun render(files: List<ReceivedFile>) {
        val first = files.first()
        val entry = VfsEntry(path = first.path, name = first.name, isDirectory = first.isDirectory,
            size = first.size)
        val isArchive = ArchiveEngine.isArchiveFile(entry.name) && !entry.isDirectory
        val anyArchive = files.any { !it.isDirectory && ArchiveEngine.isArchiveFile(it.name) }
        val isApk = entry.extension.equals("apk", ignoreCase = true) && !entry.isDirectory
        val isText = FileOpener.isTextFile(entry) ||
            entry.extension in setOf("xml", "properties", "ini", "conf", "smali", "yml", "yaml")
        val isMedia = com.freefile.ui.media.MediaItem.isMedia(entry.extension) && !entry.isDirectory
        val isPdf = entry.extension.equals("pdf", ignoreCase = true) && !entry.isDirectory
        val isHtml = (entry.extension.equals("html", ignoreCase = true) ||
            entry.extension.equals("htm", ignoreCase = true) ||
            entry.extension.equals("mhtml", ignoreCase = true) ||
            entry.extension.equals("xhtml", ignoreCase = true)) && !entry.isDirectory
        val isDocx = entry.extension.equals("docx", ignoreCase = true) && !entry.isDirectory
        val scope = lifecycleScope
        fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        fun launchMediaViewer() {
            mediaLauncher.launch(
                Intent(this, com.freefile.ui.media.MediaViewerActivity::class.java)
                    .putStringArrayListExtra(
                        com.freefile.ui.media.MediaViewerActivity.EXTRA_FILE_PATHS,
                        ArrayList(files.map { it.path })
                    )
                    .putExtra(com.freefile.ui.media.MediaViewerActivity.EXTRA_INITIAL_INDEX, 0)
            )
        }

        fun launchPdfViewer() {
            startActivity(
                Intent(this, com.freefile.tools.pdfviewer.PdfViewerActivity::class.java)
                    .putExtra(com.freefile.tools.pdfviewer.PdfViewerActivity.EXTRA_PDF_PATH, first.path)
            )
            finish()
        }

        fun launchWebViewer() {
            startActivity(
                Intent(this, com.freefile.tools.web.WebViewerActivity::class.java)
                    .putExtra(com.freefile.tools.web.WebViewerActivity.EXTRA_PATH, first.path)
            )
            finish()
        }

        fun launchDocxViewer() {
            startActivity(
                Intent(this, com.freefile.tools.web.WebViewerActivity::class.java)
                    .putExtra(com.freefile.tools.web.WebViewerActivity.EXTRA_DOCX_PATH, first.path)
            )
            finish()
        }

        // 压缩：直接入队 TransferManager，完成后 finish 回主页看进度卡
        fun doCompress(destination: String, format: CompressionEngine.Format,
                       level: CompressionEngine.Level, splitMb: Long) {
            val entries = files.map { VfsEntry(path = it.path, name = it.name, isDirectory = it.isDirectory) }
            val options = CompressionEngine.Options(
                format = format, level = level, splitMb = splitMb
            )
            TransferManager.enqueueCompress(entries, destination, options) { ok, msg ->
                runOnUiThread {
                    if (ok) {
                        toast(getString(R.string.msg_compress_done))
                        FileChangeBus.notify(java.io.File(destination).parent ?: destination)
                    } else toast(msg)
                }
            }
            finish()
        }

        // 解压：对所有压缩包入队
        fun doExtract(targetDir: String) {
            val archives = files.filter { !it.isDirectory && ArchiveEngine.isArchiveFile(it.name) }
            archives.forEach { arc ->
                TransferManager.enqueueExtract(arc.path, targetDir) { ok, msg ->
                    runOnUiThread {
                        if (ok) {
                            toast(getString(R.string.msg_extract_done))
                            FileChangeBus.notify(targetDir)
                        } else toast(msg)
                    }
                }
            }
            finish()
        }

        // 加密单个文件：后台执行，进度通过 cryptoState 驱动 UI
        fun doEncrypt(password: String, deleteSource: Boolean) {
            val target = files.firstOrNull { !it.isDirectory } ?: run {
                toast(getString(R.string.crypto_no_file)); return
            }
            val input = File(target.path)
            val output = FileCrypto.encryptedOutputFor(input)
            if (output.exists()) {
                toast(getString(R.string.crypto_output_exists, output.name)); return
            }
            val pwd = password.toCharArray()
            cryptoState = CryptoState.Running(0, isEncrypt = true)
            scope.launch(Dispatchers.IO) {
                try {
                    var lastPercent = -1
                    FileCrypto.encrypt(input, output, pwd) { p ->
                        // 仅在百分比变化时切到主线程更新，避免大量回调淹没 UI
                        if (p != lastPercent) {
                            lastPercent = p
                            runOnUiThread { cryptoState = CryptoState.Running(p, true) }
                        }
                    }
                    if (deleteSource) {
                        val deleted = try { input.delete() } catch (_: Exception) { false }
                        if (!deleted) {
                            runOnUiThread { toast(getString(R.string.crypto_delete_source_failed)) }
                        }
                    }
                    runOnUiThread {
                        cryptoState = CryptoState.Idle
                        toast(getString(R.string.crypto_encrypt_done, output.name))
                        FileChangeBus.notify(output.parent ?: input.parent ?: "")
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        cryptoState = CryptoState.Idle
                        toast(e.message ?: getString(R.string.crypto_encrypt_failed))
                    }
                } finally {
                    java.util.Arrays.fill(pwd, ' ')
                }
            }
        }

        // 解密单个 .cry 文件：后台执行，输出到源文件所在目录
        fun doDecrypt(password: String) {
            val target = files.firstOrNull { FileCrypto.isEncryptedFile(it.name) } ?: run {
                toast(getString(R.string.crypto_no_cry_file)); return
            }
            val input = File(target.path)
            val outputDir = input.parentFile ?: File(files.first().path).parentFile ?: return
            val pwd = password.toCharArray()
            cryptoState = CryptoState.Running(0, isEncrypt = false)
            scope.launch(Dispatchers.IO) {
                try {
                    var lastPercent = -1
                    val out = FileCrypto.decrypt(input, outputDir, pwd) { p ->
                        if (p != lastPercent) {
                            lastPercent = p
                            runOnUiThread { cryptoState = CryptoState.Running(p, false) }
                        }
                    }
                    runOnUiThread {
                        cryptoState = CryptoState.Idle
                        toast(getString(R.string.crypto_decrypt_done, out.name))
                        FileChangeBus.notify(outputDir.absolutePath)
                        finish()
                    }
                } catch (e: FileCrypto.CryptoException) {
                    runOnUiThread {
                        cryptoState = CryptoState.Idle
                        toast(getString(R.string.crypto_wrong_password))
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        cryptoState = CryptoState.Idle
                        toast(e.message ?: getString(R.string.crypto_decrypt_failed))
                    }
                } finally {
                    java.util.Arrays.fill(pwd, ' ')
                }
            }
        }

        // 默认压缩目标路径
        fun defaultCompressDestination(): String {
            val firstPath = first.path
            val parent = firstPath.substringBeforeLast('/').ifEmpty { "/" }
            val baseName = if (first.isDirectory) first.name else first.name.substringBeforeLast('.')
            return "$parent/$baseName.zip"
        }

        // 默认解压目标路径
        fun defaultExtractDir(archivePath: String): String {
            val name = archivePath.substringAfterLast('/')
            val lower = name.lowercase()
            val base = when {
                lower.endsWith(".tar.gz") -> name.dropLast(7)
                lower.endsWith(".tar.bz2") -> name.dropLast(8)
                lower.endsWith(".tar.xz") -> name.dropLast(7)
                else -> name.substringBeforeLast('.')
            }.ifEmpty { name }
            val parent = archivePath.substringBeforeLast('/').ifEmpty { "/" }
            return if (parent == "/") "/$base" else "$parent/$base"
        }

        setContent {
            FreeFileTheme {
                OpenWithScreen(
                    files = files,
                    entry = entry,
                    isArchive = isArchive,
                    anyArchive = anyArchive,
                    isApk = isApk,
                    isMedia = isMedia,
                    isPdf = isPdf,
                    isHtml = isHtml,
                    isDocx = isDocx,
                    defaultCompressDestination = defaultCompressDestination(),
                    defaultExtractDir = defaultExtractDir(first.path),
                    onShare = {
                        scope.launch {
                            val entries = files.map {
                                VfsEntry(path = it.path, name = it.name, isDirectory = it.isDirectory)
                            }
                            FileOpener.shareFiles(this@OpenWithActivity, entries, ::toast)
                            finish()
                        }
                    },
                    onOpenWithMime = {
                        scope.launch {
                            files.forEach {
                                val e = VfsEntry(path = it.path, name = it.name, isDirectory = it.isDirectory)
                                FileOpener.openWithMime(this@OpenWithActivity, e, ::toast)
                            }
                            finish()
                        }
                    },
                    onTextEditor = {
                        if (!isText) toast(getString(R.string.msg_open_non_local))
                        val acodePackage = "com.foxdebug.acode"
                        val isAcodeInstalled = try {
                            packageManager.getPackageInfo(acodePackage, 0); true
                        } catch (e: PackageManager.NameNotFoundException) { false }
                        if (isAcodeInstalled) {
                            val file = File(entry.path)
                            val fileUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                            val mimeType = when (file.extension.lowercase()) {
                                "txt" -> "text/plain"; "js" -> "application/javascript"
                                "html", "htm" -> "text/html"; "css" -> "text/css"
                                "json" -> "application/json"; "xml" -> "text/xml"
                                "py" -> "text/x-python"; "java" -> "text/x-java"
                                "kt", "kts" -> "text/x-kotlin"; "md" -> "text/markdown"
                                "log" -> "text/plain"; else -> "*/*"
                            }
                            val intent = Intent(Intent.ACTION_EDIT).apply {
                                setDataAndType(fileUri, mimeType)
                                setPackage(acodePackage)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            }
                            try {
                                startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                val fallback = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(fileUri, mimeType); setPackage(acodePackage)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                                try { startActivity(fallback) }
                                catch (e2: ActivityNotFoundException) {
                                    toast(getString(R.string.msg_acode_not_found))
                                    startActivity(Intent(this, ToolTrayActivity::class.java))
                                }
                            }
                        } else {
                            toast("Please install Acode")
                            startActivity(Intent(this, ToolTrayActivity::class.java))
                        }
                        finish()
                    },
                    onToolTray = {
                        startActivity(Intent(this, ToolTrayActivity::class.java)
                            .putStringArrayListExtra(ToolTrayActivity.EXTRA_PENDING_FILE_PATHS,
                                ArrayList(files.map { it.path })))
                        finish()
                    },
                    onTerminal = {
                        startActivity(Intent(this, TerminalActivity::class.java)
                            .putStringArrayListExtra(TerminalActivity.EXTRA_INITIAL_PATHS,
                                ArrayList(files.map { it.path })))
                        finish()
                    },
                    onExtract = { /* handled by dialog in composable */ },
                    onVirtualBox = {
                        startActivity(Intent(this, VirtualBoxMainActivity::class.java)
                            .putExtra(VirtualBoxMainActivity.EXTRA_APK_PATH, entry.path))
                        finish()
                    },
                    onMediaViewer = { launchMediaViewer() },
                    onPdfViewer = { launchPdfViewer() },
                    onWebViewer = { launchWebViewer() },
                    onDocxViewer = { launchDocxViewer() },
                    onCompressConfirmed = { dest, fmt, lvl, split -> doCompress(dest, fmt, lvl, split) },
                    onExtractConfirmed = { dir -> doExtract(dir) },
                    cryptoState = cryptoState,
                    onEncryptConfirmed = { pwd, del -> doEncrypt(pwd, del) },
                    onDecryptConfirmed = { pwd -> doDecrypt(pwd) }
                )
            }
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_PATHS = "file_paths"
        const val EXTRA_EXTRACT_PATH = "extract_path"
        const val EXTRA_DELETED_PATHS = "deleted_paths"
    }
}

private enum class PendingDialog { None, Compress, Extract, Encrypt, Decrypt }

/** 加解密任务的 UI 状态，驱动进度覆盖层。 */
private sealed class CryptoState {
    object Idle : CryptoState()
    data class Running(val percent: Int, val isEncrypt: Boolean) : CryptoState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenWithScreen(
    files: List<ReceivedFile>,
    entry: VfsEntry,
    isArchive: Boolean,
    anyArchive: Boolean,
    isApk: Boolean,
    isMedia: Boolean,
    isPdf: Boolean,
    isHtml: Boolean,
    isDocx: Boolean,
    defaultCompressDestination: String,
    defaultExtractDir: String,
    onShare: () -> Unit,
    onOpenWithMime: () -> Unit,
    onTextEditor: () -> Unit,
    onToolTray: () -> Unit,
    onTerminal: () -> Unit,
    onExtract: () -> Unit,
    onVirtualBox: () -> Unit,
    onMediaViewer: () -> Unit,
    onPdfViewer: () -> Unit,
    onWebViewer: () -> Unit,
    onDocxViewer: () -> Unit,
    onCompressConfirmed: (destination: String, format: CompressionEngine.Format,
        level: CompressionEngine.Level, splitMb: Long) -> Unit,
    onExtractConfirmed: (targetDir: String) -> Unit,
    cryptoState: CryptoState,
    onEncryptConfirmed: (password: String, deleteSource: Boolean) -> Unit,
    onDecryptConfirmed: (password: String) -> Unit
) {
    var pending by remember { mutableStateOf(PendingDialog.None) }
    val navBlue = Color(0xFF1976D2)

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = navBlue,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                title = {
                    Text(
                        text = stringResource(R.string.open_with_title),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth()) {
            // 顶部接收到的文件列表
            if (files.isNotEmpty()) {
                ReceivedFilesList(files)
            }
            // 工具列表：LazyColumn + 显式滚动条
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                item {
                    OpenWithItem(icon = Icons.Rounded.Share,
                        title = stringResource(R.string.open_with_share), onClick = onShare)
                }
                item {
                    OpenWithItem(icon = Icons.Rounded.Apps,
                        title = stringResource(R.string.open_with_mime), onClick = onOpenWithMime)
                }
                item {
                    OpenWithItem(icon = Icons.Rounded.Edit,
                        title = stringResource(R.string.builtin_chooser_text_editor), onClick = onTextEditor)
                }
                item {
                    OpenWithItem(icon = Icons.Rounded.Apps,
                        title = stringResource(R.string.builtin_chooser_tools), onClick = onToolTray)
                }
                item {
                    OpenWithItem(icon = Icons.Rounded.Terminal,
                        title = stringResource(R.string.builtin_chooser_terminal), onClick = onTerminal)
                }
                // 压缩文件/文件夹
                item {
                    OpenWithItem(icon = Icons.Rounded.Archive,
                        title = stringResource(R.string.open_with_compress),
                        onClick = { pending = PendingDialog.Compress })
                }
                // 解压（仅当存在压缩包时启用）
                item {
                    OpenWithItem(icon = Icons.Rounded.Archive,
                        title = stringResource(R.string.open_with_extract),
                        enabled = anyArchive,
                        onClick = { pending = PendingDialog.Extract })
                }
                // 文件加密（单个非 .cry 文件）
                item {
                    val canEncrypt = files.any { !it.isDirectory && !FileCrypto.isEncryptedFile(it.name) }
                    OpenWithItem(icon = Icons.Rounded.Lock,
                        title = stringResource(R.string.crypto_encrypt),
                        enabled = canEncrypt,
                        onClick = { pending = PendingDialog.Encrypt })
                }
                // 文件解密（.cry 文件）
                item {
                    val canDecrypt = files.any { FileCrypto.isEncryptedFile(it.name) }
                    OpenWithItem(icon = Icons.Rounded.LockOpen,
                        title = stringResource(R.string.crypto_decrypt),
                        enabled = canDecrypt,
                        onClick = { pending = PendingDialog.Decrypt })
                }
                // 媒体查看器
                item {
                    OpenWithItem(icon = Icons.Rounded.PlayCircle,
                        title = stringResource(R.string.open_with_media_viewer),
                        enabled = isMedia, onClick = onMediaViewer)
                }
                // PDF 查看器
                item {
                    OpenWithItem(icon = Icons.Rounded.PictureAsPdf,
                        title = stringResource(R.string.open_with_pdf_viewer),
                        enabled = isPdf, onClick = onPdfViewer)
                }
                // 网页查看器
                item {
                    OpenWithItem(icon = Icons.Rounded.Language,
                        title = stringResource(R.string.open_with_web_viewer),
                        enabled = isHtml, onClick = onWebViewer)
                }
                // DOCX 查看器（已隐藏，后期再开放）
                // item {
                //     OpenWithItem(icon = Icons.Rounded.Description,
                //         title = stringResource(R.string.open_with_docx_viewer),
                //         enabled = isDocx, onClick = onDocxViewer)
                // }
                // VirtualBox（已隐藏）
                // item { OpenWithItem(icon = Icons.Rounded.Android, ...) }
                }
                // 右侧自绘滚动条
                ListScrollbar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }

    when (pending) {
        PendingDialog.Compress -> CompressDialog(
            count = files.size,
            defaultDestination = defaultCompressDestination,
            onConfirm = { dest, fmt, lvl, split ->
                onCompressConfirmed(dest, fmt, lvl, split)
                pending = PendingDialog.None
            },
            onDismiss = { pending = PendingDialog.None }
        )
        PendingDialog.Extract -> ConfirmExtractDialog(
            archiveName = if (files.count { !it.isDirectory && ArchiveEngine.isArchiveFile(it.name) } > 1)
                stringResource(R.string.dlg_extract_multiple,
                    files.count { !it.isDirectory && ArchiveEngine.isArchiveFile(it.name) })
            else files.first { !it.isDirectory && ArchiveEngine.isArchiveFile(it.name) }.name,
            defaultTargetDir = defaultExtractDir,
            onConfirm = { dir ->
                onExtractConfirmed(dir)
                pending = PendingDialog.None
            },
            onDismiss = { pending = PendingDialog.None }
        )
        PendingDialog.Encrypt -> EncryptDialog(
            onConfirm = { pwd, deleteSource ->
                onEncryptConfirmed(pwd, deleteSource)
                pending = PendingDialog.None
            },
            onDismiss = { pending = PendingDialog.None }
        )
        PendingDialog.Decrypt -> DecryptDialog(
            onConfirm = { pwd ->
                onDecryptConfirmed(pwd)
                pending = PendingDialog.None
            },
            onDismiss = { pending = PendingDialog.None }
        )
        PendingDialog.None -> {}
    }

    // 加解密进度覆盖层
    if (cryptoState is CryptoState.Running) {
        CryptoProgressOverlay(state = cryptoState)
    }
}

@Composable
private fun ReceivedFilesList(files: List<ReceivedFile>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.open_with_received_count, files.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        files.take(10).forEach { f ->
            ReceivedFileRow(f)
        }
        if (files.size > 10) {
            Text(
                stringResource(R.string.open_with_received_more, files.size - 10),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun ReceivedFileRow(f: ReceivedFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (f.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Archive,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            val sizeText = if (f.isDirectory) stringResource(R.string.open_with_type_folder)
            else Format.size(f.size)
            Text(
                "${f.typeLabel} · $sizeText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OpenWithItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, enabled = enabled)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 基于 LazyListState 的自绘滚动条（Android 无 foundation VerticalScrollbar）。 */
@Composable
private fun ListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val layoutInfo = state.layoutInfo
    val drawScrollbar = layoutInfo.totalItemsCount > 0 &&
        layoutInfo.visibleItemsInfo.isNotEmpty() &&
        layoutInfo.totalItemsCount > layoutInfo.visibleItemsInfo.size

    if (!drawScrollbar) return

    val totalCount = layoutInfo.totalItemsCount
    val visibleCount = layoutInfo.visibleItemsInfo.size
    val firstVisible = state.firstVisibleItemIndex
    val scrollOffset = state.firstVisibleItemScrollOffset
    val firstItemSize = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1

    androidx.compose.foundation.Canvas(
        modifier = modifier.width(4.dp)
    ) {
        val trackHeight = size.height
        val viewportFraction = visibleCount.toFloat() / totalCount.toFloat()
        val thumbHeight = (trackHeight * viewportFraction).coerceAtLeast(32.dp.toPx())
        // 当前滚动进度（含 item 内部偏移）
        val progress = (firstVisible + scrollOffset.toFloat() / firstItemSize.coerceAtLeast(1)) /
            totalCount.toFloat()
        val thumbY = (progress * trackHeight).coerceIn(0f, trackHeight - thumbHeight)

        // 轨道
        drawRoundRect(
            color = Color.Gray.copy(alpha = 0.18f),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
        // 拇指
        drawRoundRect(
            color = Color.Gray.copy(alpha = 0.55f),
            topLeft = androidx.compose.ui.geometry.Offset(0f, thumbY),
            size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}

/** 密码强度：弱/中/强 */
private enum class PwdStrength { WEAK, MEDIUM, STRONG }

private fun evaluateStrength(pwd: String): PwdStrength = when {
    pwd.length < 6 -> PwdStrength.WEAK
    pwd.length >= 10 && pwd.any { it.isUpperCase() } && pwd.any { it.isLowerCase() } &&
        pwd.any { it.isDigit() } && pwd.any { !it.isLetterOrDigit() } -> PwdStrength.STRONG
    else -> PwdStrength.MEDIUM
}

/** 加密对话框：密码 + 确认密码 + 强度提示 + 删除源文件复选框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EncryptDialog(
    onConfirm: (password: String, deleteSource: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(false) }
    val strength = evaluateStrength(password)
    val match = password.isNotEmpty() && password == confirm

    val strengthColor = when (strength) {
        PwdStrength.WEAK -> Color(0xFFE53935)
        PwdStrength.MEDIUM -> Color(0xFFFB8C00)
        PwdStrength.STRONG -> Color(0xFF43A047)
    }
    val strengthLabel = when (strength) {
        PwdStrength.WEAK -> stringResource(R.string.crypto_strength_weak)
        PwdStrength.MEDIUM -> stringResource(R.string.crypto_strength_medium)
        PwdStrength.STRONG -> stringResource(R.string.crypto_strength_strong)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crypto_encrypt)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.crypto_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (password.isNotEmpty()) {
                    Text(strengthLabel, color = strengthColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.crypto_confirm_password)) },
                    singleLine = true,
                    isError = confirm.isNotEmpty() && !match,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (confirm.isNotEmpty() && !match) {
                    Text(stringResource(R.string.crypto_password_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it })
                    Text(stringResource(R.string.crypto_delete_source),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password, deleteSource) },
                enabled = match
            ) { Text(stringResource(R.string.crypto_encrypt_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** 解密对话框：仅密码输入 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecryptDialog(
    onConfirm: (password: String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crypto_decrypt)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.crypto_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty()
            ) { Text(stringResource(R.string.crypto_decrypt_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** 加解密进度全屏覆盖层：进度条 + 百分比 + 不可取消 */
@Composable
private fun CryptoProgressOverlay(state: CryptoState.Running) {
    val label = if (state.isEncrypt) stringResource(R.string.crypto_encrypting)
    else stringResource(R.string.crypto_decrypting)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${state.percent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
