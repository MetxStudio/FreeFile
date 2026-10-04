package com.freefile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.freefile.R
import com.freefile.tools.richeditor.RichEditorActivity
import com.freefile.tools.virtualbox.MainActivity as VirtualBoxMainActivity
import com.freefile.ui.theme.FreeFileTheme
import com.freefile.util.FileOpener
import com.freefile.vfs.ArchiveEngine
import com.freefile.vfs.VfsEntry
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

/**
 * 打开方式 Activity：单击文件时弹出，集中承载原主页的「打开方式」与「内置功能打开」两个对话框。
 *
 * Intent extras:
 *  - [EXTRA_FILE_PATH]  : String，要打开的文件路径（单文件，向后兼容）
 *  - [EXTRA_FILE_PATHS] : ArrayList<String>，要打开的文件路径列表（多文件）
 *
 * 结果回传（仅当选「解压此压缩包」时）：
 *  - resultCode = [RESULT_OK]
 *  - [EXTRA_EXTRACT_PATH] : String，需要解压的压缩包路径，由主页据其触发 PendingAction.Extract
 */
class OpenWithActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 支持多文件：优先取列表，回退到单文件
        val filePaths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS)
            ?: listOfNotNull(intent.getStringExtra(EXTRA_FILE_PATH))
        if (filePaths.isEmpty()) {
            finish()
            return
        }
        // 以第一个文件作为主展示条目
        val filePath = filePaths.first()
        val name = filePath.substringAfterLast('/')
        val entry = VfsEntry(path = filePath, name = name, isDirectory = false)
        val isArchive = ArchiveEngine.isArchiveFile(entry.name) && !entry.isDirectory
        val isApk = entry.extension.equals("apk", ignoreCase = true) && !entry.isDirectory
        val isText = FileOpener.isTextFile(entry) ||
            entry.extension in setOf("xml", "properties", "ini", "conf", "smali", "yml", "yaml")
        val isMedia = com.freefile.ui.media.MediaItem.isMedia(entry.extension) && !entry.isDirectory
        val scope = lifecycleScope
        fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        // 媒体查看器结果回传：将被删除的路径转发给主页刷新
        val mediaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                val deleted = res.data?.getStringArrayListExtra(
                    com.freefile.ui.media.MediaViewerActivity.EXTRA_DELETED_PATHS
                )
                if (!deleted.isNullOrEmpty()) {
                    setResult(
                        RESULT_OK,
                        Intent().putStringArrayListExtra(EXTRA_DELETED_PATHS, deleted)
                    )
                }
            }
            finish()
        }

        fun launchMediaViewer() {
            mediaLauncher.launch(
                Intent(this, com.freefile.ui.media.MediaViewerActivity::class.java)
                    .putStringArrayListExtra(
                        com.freefile.ui.media.MediaViewerActivity.EXTRA_FILE_PATHS,
                        ArrayList(filePaths)
                    )
                    .putExtra(
                        com.freefile.ui.media.MediaViewerActivity.EXTRA_INITIAL_INDEX,
                        0
                    )
            )
        }

        setContent {
            FreeFileTheme {
                OpenWithScreen(
                    entry = entry,
                    isArchive = isArchive,
                    isApk = isApk,
                    isMedia = isMedia,
                    onShare = {
                        scope.launch {
                            val entries = filePaths.map { p ->
                                VfsEntry(path = p, name = p.substringAfterLast('/'), isDirectory = false)
                            }
                            FileOpener.shareFiles(this@OpenWithActivity, entries, ::toast)
                            finish()
                        }
                    },
                    onOpenWithMime = {
                        scope.launch {
                            filePaths.forEach { p ->
                                val e = VfsEntry(path = p, name = p.substringAfterLast('/'), isDirectory = false)
                                FileOpener.openWithMime(this@OpenWithActivity, e, ::toast)
                            }
                            finish()
                        }
                    },
                    onTextEditor = {
                        // 非文本文件先 toast 提示再打开（编辑器按字节读，给用户提示机会）
                        if (!isText) toast(getString(R.string.msg_open_non_local))

                        val acodePackage = "com.foxdebug.acode"

                        // 1. 检测 Acode 是否安装
                        val isAcodeInstalled = try {
                            packageManager.getPackageInfo(acodePackage, 0)
                            true
                        } catch (e: PackageManager.NameNotFoundException) {
                            false
                        }

                        if (isAcodeInstalled) {
                            // 2. 为文件生成 content:// URI（需提前配置 FileProvider）
                            val file = File(entry.path)
                            val fileUri = FileProvider.getUriForFile(
                                this,
                                "$packageName.fileprovider", // 与 AndroidManifest 中 FileProvider 的 authorities 一致
                                file
                            )

                            // 根据扩展名设置具体 MIME 类型，帮助 Acode 正确识别
                            val mimeType = when (file.extension.lowercase()) {
                                "txt" -> "text/plain"
                                "js" -> "application/javascript"
                                "html", "htm" -> "text/html"
                                "css" -> "text/css"
                                "json" -> "application/json"
                                "xml" -> "text/xml"
                                "py" -> "text/x-python"
                                "java" -> "text/x-java"
                                "kt", "kts" -> "text/x-kotlin"
                                "md" -> "text/markdown"
                                "log" -> "text/plain"
                                else -> "*/*"
                            }

                            // 3. 构建 Intent 传递给 Acode，优先使用 ACTION_EDIT 请求可编辑打开
                            val intent = Intent(Intent.ACTION_EDIT).apply {
                                setDataAndType(fileUri, mimeType)
                                setPackage(acodePackage)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            }

                            try {
                                startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                // 如果 ACTION_EDIT 不被支持，回退到 ACTION_VIEW
                                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(fileUri, mimeType)
                                    setPackage(acodePackage)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                                try {
                                    startActivity(fallbackIntent)
                                } catch (e2: ActivityNotFoundException) {
                                    toast(getString(R.string.msg_acode_not_found))
                                    startActivity(Intent(this, ToolTrayActivity::class.java))
                                }
                            }
                        } else {
                            // 4. Acode 未安装，跳转到 ToolTrayActivity 提醒安装
                            toast("Please install Acode")
                            startActivity(Intent(this, ToolTrayActivity::class.java))
                        }

                        finish()
                    },
                    onToolTray = {
                        // 传递所有选中文件给工具托盘
                        startActivity(
                            Intent(this, ToolTrayActivity::class.java)
                                .putStringArrayListExtra(
                                    ToolTrayActivity.EXTRA_PENDING_FILE_PATHS,
                                    ArrayList(filePaths)
                                )
                        )
                        finish()
                    },
                    onTerminal = {
                        startActivity(
                            Intent(this, TerminalActivity::class.java)
                                .putStringArrayListExtra(
                                    TerminalActivity.EXTRA_INITIAL_PATHS,
                                    ArrayList(filePaths)
                                )
                        )
                        finish()
                    },
                    onExtract = {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_EXTRACT_PATH, entry.path))
                        finish()
                    },
                    onVirtualBox = {
                        startActivity(
                            Intent(this, VirtualBoxMainActivity::class.java)
                                .putExtra(VirtualBoxMainActivity.EXTRA_APK_PATH, entry.path)
                        )
                        finish()
                    },
                    onMediaViewer = { launchMediaViewer() }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenWithScreen(
    entry: VfsEntry,
    isArchive: Boolean,
    isApk: Boolean,
    isMedia: Boolean,
    onShare: () -> Unit,
    onOpenWithMime: () -> Unit,
    onTextEditor: () -> Unit,
    onToolTray: () -> Unit,
    onTerminal: () -> Unit,
    onExtract: () -> Unit,
    onVirtualBox: () -> Unit,
    onMediaViewer: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = Color.Black),
                title = {
                    Text(
                        text = stringResource(R.string.open_with_title) + " · " + entry.name,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxWidth()) {
            // ① 分享文件（系统分享 ACTION_SEND）
            item {
                OpenWithItem(
                    icon = Icons.Rounded.Share,
                    title = stringResource(R.string.open_with_share),
                    onClick = onShare
                )
            }
            // ② 用系统应用打开（MIME + chooser）
            item {
                OpenWithItem(
                    icon = Icons.Rounded.Apps,
                    title = stringResource(R.string.open_with_mime),
                    onClick = onOpenWithMime
                )
            }
            // ③ 文本编辑器
            item {
                OpenWithItem(
                    icon = Icons.Rounded.Edit,
                    title = stringResource(R.string.builtin_chooser_text_editor),
                    onClick = onTextEditor
                )
            }
            // ④ 工具托盘
            item {
                OpenWithItem(
                    icon = Icons.Rounded.Apps,
                    title = stringResource(R.string.builtin_chooser_tools),
                    onClick = onToolTray
                )
            }
            // ⑤ 终端
            item {
                OpenWithItem(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.builtin_chooser_terminal),
                    onClick = onTerminal
                )
            }
            // ⑥ 解压此压缩包（仅当选中压缩包时启用）
            item {
                OpenWithItem(
                    icon = Icons.Rounded.Archive,
                    title = stringResource(R.string.builtin_chooser_extract),
                    enabled = isArchive,
                    onClick = onExtract
                )
            }
            // ⑦ 媒体查看器（仅当选中媒体文件时启用）
            item {
                OpenWithItem(
                    icon = Icons.Rounded.PlayCircle,
                    title = stringResource(R.string.open_with_media_viewer),
                    enabled = isMedia,
                    onClick = onMediaViewer
                )
            }
            // ⑦ VirtualBox 免安装运行（已隐藏）
            // item {
            //     OpenWithItem(
            //         icon = Icons.Rounded.Android,
            //         title = stringResource(R.string.builtin_chooser_virtualbox),
            //         enabled = isApk,
            //         onClick = onVirtualBox
            //     )
            // }
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
