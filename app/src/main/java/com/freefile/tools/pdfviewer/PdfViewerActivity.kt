package com.freefile.tools.pdfviewer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.freefile.ui.theme.FreeFileTheme
import java.io.File

/**
 * PDF 查看器入口：PDF 的 Uri/路径由外部传入（OpenWithActivity / 其他 App / Intent）。
 *
 * Intent extras（见 [PdfReaderScreen] 空态后备手动选择）：
 *  - [EXTRA_PDF_URI]  : Uri 对象（Parcelable）
 *  - [EXTRA_PDF_PATH] : String 路径（绝对路径 / content:// / file://）
 *  - ACTION_VIEW(data): 其他应用"打开方式"传入 content:// 或 file://
 *
 * launchMode=singleTop：再次传入新文件时走 [onNewIntent] 更新界面。
 */
class PdfViewerActivity : AppCompatActivity() {

    /** 用 Compose State 持有 Uri，便于 onNewIntent 时更新 UI。 */
    private val pdfUriState = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            FreeFileTheme {
                PdfViewerScreen(
                    externalUri = pdfUriState.value,
                    onBack = { finish() }
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
        val uri = extractUriFromIntent(intent) ?: return
        ensureReadPermission(this, uri)
        pdfUriState.value = uri
    }

    /**
     * 支持 3 种传入方式：
     *  1. ACTION_VIEW + data            → 其他应用"打开方式"
     *  2. EXTRA_PDF_URI (Uri)           → 显式 Intent 传 Parcelable
     *  3. EXTRA_PDF_PATH (String)       → 显式 Intent 传路径字符串
     */
    private fun extractUriFromIntent(intent: Intent?): Uri? {
        intent ?: return null

        // 1：ACTION_VIEW
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { return it }
        }

        // 2：Uri 类型 extra
        val uriExtra: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PDF_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PDF_URI)
        }
        uriExtra?.let { return it }

        // 3：String 路径 extra
        val pathExtra = intent.getStringExtra(EXTRA_PDF_PATH)
        if (!pathExtra.isNullOrBlank()) return pathToUri(pathExtra)

        return null
    }

    companion object {
        const val EXTRA_PDF_URI = "extra_pdf_uri"
        const val EXTRA_PDF_PATH = "extra_pdf_path"
    }
}

/** 字符串路径 → Uri */
fun pathToUri(path: String): Uri = when {
    path.startsWith("content://") -> Uri.parse(path)
    path.startsWith("file://") -> Uri.parse(path)
    path.startsWith("/") -> Uri.fromFile(File(path))
    else -> Uri.parse(path)
}

/**
 * 对 content:// Uri 申请持久化读取权限。
 * 若调用方未授予 FLAG_GRANT_PERSISTABLE_URI_PERMISSION，会抛 SecurityException，静默忽略即可（临时授权）。
 */
fun ensureReadPermission(context: Context, uri: Uri) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        // 临时授权，忽略
    }
}
