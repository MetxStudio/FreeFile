package com.freefile.ui.media

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.freefile.R
import com.freefile.ui.theme.FreeFileTheme
import java.io.File

/**
 * 媒体查看器入口：接收文件路径列表与初始索引，统一展示图片/视频/音频。
 *
 * Intent extras:
 *  - [EXTRA_FILE_PATHS]  : ArrayList<String>，文件路径列表
 *  - [EXTRA_INITIAL_INDEX]: Int，初始展示索引，默认 0
 *
 * 结果回传：
 *  - resultCode = RESULT_OK
 *  - [EXTRA_DELETED_PATHS]: ArrayList<String>，被删除的文件路径，供主页刷新列表
 */
class MediaViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: emptyList()
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0).coerceIn(0, paths.size - 1)

        if (paths.isEmpty()) {
            finish()
            return
        }

        val items = paths.map { MediaItem.fromPath(it) }

        setContent {
            FreeFileTheme {
                MediaViewerScreen(
                    items = items,
                    initialIndex = initialIndex,
                    onBack = { finish() },
                    onShare = { item -> shareItem(item) },
                    onDelete = { item -> deleteItem(item) },
                    onDetails = { item -> showDetails(item) }
                )
            }
        }
    }

    private fun shareItem(item: MediaItem) {
        val file = item.path?.let { File(it) }
        if (file == null || !file.exists()) {
            toast(getString(R.string.media_open_failed))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, item.title))
        } catch (e: Exception) {
            toast(getString(R.string.msg_no_app))
        }
    }

    private fun deleteItem(item: MediaItem) {
        val file = item.path?.let { File(it) }
        if (file == null || !file.exists()) {
            toast(getString(R.string.media_open_failed))
            return
        }
        val ok = file.delete()
        if (ok) {
            toast(getString(R.string.media_delete_done))
            setResult(
                RESULT_OK,
                Intent().putStringArrayListExtra(EXTRA_DELETED_PATHS, arrayListOf(item.path))
            )
        } else {
            toast(getString(R.string.media_delete_failed))
        }
    }

    private fun showDetails(item: MediaItem) {
        val sizeText = android.text.format.Formatter.formatShortFileSize(this, item.size)
        val msg = buildString {
            append(item.title).append('\n')
            append(getString(R.string.media_detail_type)).append(' ').append(item.type.name).append('\n')
            append(getString(R.string.media_detail_mime)).append(' ').append(item.mimeType ?: "-").append('\n')
            append(getString(R.string.media_detail_size)).append(' ').append(sizeText).append('\n')
            item.path?.let { append(getString(R.string.media_detail_path)).append(' ').append(it) }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.media_detail_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_FILE_PATHS = "media_file_paths"
        const val EXTRA_INITIAL_INDEX = "media_initial_index"
        const val EXTRA_DELETED_PATHS = "media_deleted_paths"
    }
}
