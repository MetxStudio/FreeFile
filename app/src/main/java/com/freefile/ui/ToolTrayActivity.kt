package com.freefile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.freefile.data.externaltools.ExternalToolsStore
import com.freefile.ui.tools.ToolTrayScreen
import com.freefile.ui.theme.FreeFileTheme

/**
 * 工具托盘 Activity：独立任务栈运行。
 *
 * Intent extra:
 *  - [EXTRA_PENDING_FILE_PATHS] : ArrayList<String>，需要处理的文件路径列表（可选）
 *  - [EXTRA_PENDING_FILE_PATH]  : String?，单文件路径（向后兼容）
 */
class ToolTrayActivity : AppCompatActivity() {

    private val storageStore by lazy { ExternalToolsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLegacyStorageIfNeeded()
        val paths = intent.getStringArrayListExtra(EXTRA_PENDING_FILE_PATHS)
            ?: listOfNotNull(intent.getStringExtra(EXTRA_PENDING_FILE_PATH))
        setContent {
            FreeFileTheme {
                ToolTrayScreen(
                    pendingFilePaths = paths,
                    onBack = { finish() },
                    onOpenDownloads = {
                        startActivity(Intent(this, DownloadToolActivity::class.java))
                    },
                    onRequestStorage = { requestExternalToolsStorage() },
                    onMessage = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQUEST_STORAGE
            )
        }
    }

    private fun requestExternalToolsStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()
        ) {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra("android.content.extra.SHOW_ADVANCED", true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, REQUEST_TREE)
            } catch (_: Exception) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ))
            }
        } else {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQUEST_TREE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    storageStore.saveTreeUri(uri)
                    Toast.makeText(this, "已授权外部工具目录", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "目录授权失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_PENDING_FILE_PATH = "pending_file_path"
        const val EXTRA_PENDING_FILE_PATHS = "pending_file_paths"
        private const val REQUEST_STORAGE = 4101
        private const val REQUEST_TREE = 4102
    }
}
