package com.freefile.tools.pdfviewer

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.freefile.R
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import java.io.File

/**
 * PDF 查看器屏幕：
 *  - 顶栏：文件名 + 当前页/总页数，返回键、打开文件（后备）
 *  - 底栏：缩小/放大 + 页码跳转 + 分享 + 详情
 *  - 主体：PDFView 渲染，垂直滚动，双击缩放
 *
 * 兼容 AndroidPdfViewer 2.0.2：监听器接口、zoomCenteredTo。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    externalUri: Uri?,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current

    // 当前生效的 Uri：外部传入优先，用户手动选择可覆盖
    var pdfUri by remember { mutableStateOf<Uri?>(externalUri) }
    var currentPage by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var pdfViewRef by remember { mutableStateOf<PDFView?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 页码跳转对话框
    var showPageJump by remember { mutableStateOf(false) }
    // 详情对话框
    var showDetails by remember { mutableStateOf(false) }

    // 外部 Uri 变化时同步（onNewIntent 场景）
    LaunchedEffect(externalUri) {
        if (externalUri != null && externalUri != pdfUri) {
            pdfUri = externalUri
            currentPage = 0
            pageCount = 0
            errorMessage = null
        }
    }

    // 后备：用户主动选择文件（SAF）
    val pickPdf = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            ensureReadPermission(context, it)
            pdfUri = it
            currentPage = 0
            pageCount = 0
            errorMessage = null
        }
    }

    val fileName = remember(pdfUri) {
        pdfUri?.let { uri ->
            if (uri.scheme == "file") {
                File(uri.path ?: return@let null).name
            } else {
                try {
                    context.contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } catch (_: Exception) {
                    null
                } ?: uri.lastPathSegment?.substringAfterLast('/')
            }
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** 分享当前 PDF：file:// 用 FileProvider 转 content://；content:// 直接分享。 */
    fun sharePdf() {
        val uri = pdfUri ?: return
        try {
            val shareUri = if (uri.scheme == "file") {
                val f = File(uri.path ?: return)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            } else uri
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, fileName))
        } catch (e: Exception) {
            toast(context.getString(R.string.msg_no_app))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1976D2),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Column {
                        Text(
                            text = fileName ?: stringResource(R.string.pdf_viewer_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (pageCount > 0) {
                            Text(
                                text = stringResource(R.string.pdf_page_of, currentPage + 1, pageCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = stringResource(R.string.pdf_open)
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (pdfUri != null && errorMessage == null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BottomAction(
                            icon = Icons.Rounded.ZoomOut,
                            label = stringResource(R.string.pdf_zoom_out)
                        ) {
                            pdfViewRef?.let { v ->
                                val center = android.graphics.PointF(v.width / 2f, v.height / 2f)
                                v.zoomCenteredTo(v.zoom / 1.4f, center)
                            }
                        }
                        BottomAction(
                            icon = Icons.Rounded.ZoomIn,
                            label = stringResource(R.string.pdf_zoom_in)
                        ) {
                            pdfViewRef?.let { v ->
                                val center = android.graphics.PointF(v.width / 2f, v.height / 2f)
                                v.zoomCenteredTo(v.zoom * 1.4f, center)
                            }
                        }
                        BottomAction(
                            icon = Icons.Rounded.EditNote,
                            label = stringResource(R.string.pdf_jump_to_page)
                        ) {
                            showPageJump = true
                        }
                        BottomAction(
                            icon = Icons.Rounded.Share,
                            label = stringResource(R.string.media_share)
                        ) {
                            sharePdf()
                        }
                        BottomAction(
                            icon = Icons.Rounded.Info,
                            label = stringResource(R.string.media_details)
                        ) {
                            showDetails = true
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            val uri = pdfUri
            if (uri == null) {
                // 空状态：无外部传入，提示选择
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.pdf_no_file))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pdf_open))
                    }
                }
            } else {
                key(uri.toString()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PDFView(ctx, null).apply {
                                setBackgroundColor(android.graphics.Color.LTGRAY)
                                loadPdf(
                                    pdfView = this,
                                    uri = uri,
                                    startPage = currentPage,
                                    onPageChanged = { page, total ->
                                        currentPage = page
                                        pageCount = total
                                    },
                                    onLoadComplete = { total -> pageCount = total },
                                    onError = { msg -> errorMessage = msg }
                                )
                                pdfViewRef = this
                            }
                        },
                        update = { pdfViewRef = it }
                    )
                }

                errorMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }

    // 页码跳转对话框
    if (showPageJump) {
        PageJumpDialog(
            currentPage = currentPage,
            pageCount = pageCount,
            onJump = { page ->
                pdfViewRef?.jumpTo(page, true)
                showPageJump = false
            },
            onDismiss = { showPageJump = false }
        )
    }

    // 详情对话框
    if (showDetails) {
        PdfDetailsDialog(
            uri = pdfUri,
            fileName = fileName,
            currentPage = currentPage,
            pageCount = pageCount,
            onDismiss = { showDetails = false }
        )
    }
}

/** 底栏按钮：图标在上，文字在下。 */
@Composable
private fun BottomAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .padding(vertical = 2.dp)
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 页码跳转对话框 */
@Composable
private fun PageJumpDialog(
    currentPage: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf((currentPage + 1).toString()) }
    val target = input.toIntOrNull()
    val valid = target != null && target in 1..pageCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_jump_to_page)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.pdf_jump_hint, pageCount)) },
                    singleLine = true,
                    isError = input.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (input.isNotEmpty() && !valid) {
                    Text(
                        stringResource(R.string.pdf_jump_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onJump(target!! - 1) },
                enabled = valid
            ) { Text(stringResource(R.string.pdf_jump_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** 详情对话框：文件名/路径/页数/大小 */
@Composable
private fun PdfDetailsDialog(
    uri: Uri?,
    fileName: String?,
    currentPage: Int,
    pageCount: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val details = remember(uri, fileName, pageCount) {
        buildString {
            append(fileName ?: "-").append('\n')
            append(context.getString(R.string.media_detail_type)).append(" PDF").append('\n')
            if (pageCount > 0) {
                append(context.getString(R.string.pdf_detail_pages))
                    .append(" $pageCount\n")
                append(context.getString(R.string.pdf_detail_current_page))
                    .append(" ${currentPage + 1}\n")
            }
            // 大小（仅 file:// 可准确获取）
            if (uri?.scheme == "file") {
                val f = File(uri.path ?: "")
                if (f.exists()) {
                    append(context.getString(R.string.media_detail_size)).append(' ')
                        .append(android.text.format.Formatter.formatShortFileSize(context, f.length()))
                        .append('\n')
                }
                append(context.getString(R.string.media_detail_path)).append(' ').append(f.absolutePath)
            } else {
                append(context.getString(R.string.media_detail_path)).append(' ').append(uri?.toString() ?: "-")
            }
        }.trimEnd('\n')
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_detail_title)) },
        text = { Text(details) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )
}

/** 抽取 PDFView 的加载配置（AndroidPdfViewer 2.0.2 API：监听器接口，无 spacing/FitPolicy） */
private fun loadPdf(
    pdfView: PDFView,
    uri: Uri,
    startPage: Int,
    onPageChanged: (Int, Int) -> Unit,
    onLoadComplete: (Int) -> Unit,
    onError: (String) -> Unit
) {
    try {
        pdfView.fromUri(uri)
            .defaultPage(startPage)
            .enableSwipe(true)
            .swipeHorizontal(false)              // 垂直滚动
            .enableDoubletap(true)
            .enableAnnotationRendering(true)     // 渲染注解
            .scrollHandle(DefaultScrollHandle(pdfView.context))
            .onPageChange(object : OnPageChangeListener {
                override fun onPageChanged(page: Int, pageCount: Int) {
                    onPageChanged(page, pageCount)
                }
            })
            .onLoad(object : OnLoadCompleteListener {
                override fun loadComplete(nbPages: Int) {
                    onLoadComplete(nbPages)
                }
            })
            .onError(object : OnErrorListener {
                override fun onError(t: Throwable?) {
                    onError(t?.message ?: t?.javaClass?.simpleName ?: "unknown")
                }
            })
            .load()
    } catch (e: Exception) {
        onError(e.message ?: e.javaClass.simpleName)
    }
}
