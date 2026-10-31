package com.freefile.tools.web

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.freefile.ui.theme.FreeFileTheme
import java.io.File

/**
 * 网页查看器入口。
 *
 * Intent extras：
 *  - ACTION_VIEW(data)            → 其他应用"打开方式"（file:///xxx.html 或 http(s)://）
 *  - [EXTRA_URL]   : String       → 显式传 URL
 *  - [EXTRA_PATH]  : String       → 显式传本地文件路径（自动转 file://）
 *
 * launchMode=singleTop：再次传入新链接时走 [onNewIntent] 新开标签。
 */
class WebViewerActivity : AppCompatActivity() {

    private val pendingUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            FreeFileTheme {
                WebViewerScreen(
                    initialUrl = pendingUrl.value,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // DOCX 查看：加载内置 docx-viewer 页面，并把文件交给页面解析
        val docxPath = intent.getStringExtra(EXTRA_DOCX_PATH)
        if (!docxPath.isNullOrBlank()) {
            // 即使文件不存在也打开查看器，页面会显示具体失败原因，便于排查
            pendingUrl.value = LocalWebServer.docxViewerUrl(File(docxPath))
            return
        }
        val url = when {
            intent.action == Intent.ACTION_VIEW -> intent.data?.let { uriToUrl(it) }
            else -> intent.getStringExtra(EXTRA_URL)
                ?: intent.getStringExtra(EXTRA_PATH)?.let { pathToFileUrl(it) }
        }
        pendingUrl.value = url
    }

    private fun uriToUrl(uri: Uri): String = when (uri.scheme) {
        "http", "https" -> uri.toString()
        "file" -> uri.toString()
        "content" -> uri.toString() // WebView 可直接加载 content://
        else -> uri.toString()
    }

    private fun pathToFileUrl(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("file://") || path.startsWith("content://") -> path
        path.startsWith("/") -> Uri.fromFile(File(path)).toString()
        else -> path
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_PATH = "extra_path"

        /** 传入 .docx 本地路径：用内置 docx-viewer 页面打开。 */
        const val EXTRA_DOCX_PATH = "extra_docx_path"
    }
}
