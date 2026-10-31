package com.freefile.tools.web

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freefile.R
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Primary = Color(0xFF1A73E8)

/**
 * 网页查看器主屏幕。
 * 左右滑动不切换标签（userScrollEnabled=false），只能通过侧拉栏切换。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WebViewerScreen(
    initialUrl: String?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()
    val store = remember { TabStore(context) }
    val webSettings = remember { WebSettingsStore(context) }

    // ---- 标签状态 ----
    val tabs = remember { mutableListOf<TabItem>().toMutableStateList() }
    var activeIndex by remember { mutableIntStateOf(0) }
    var idGen by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val webViews = remember { mutableStateMapOf<Long, WebView>() }
    // 每个标签的主文档加载错误（诊断用，页面底部显示）
    val loadErrors = remember { mutableStateMapOf<Long, String>() }
    // 地址弹窗
    var showAddressDialog by remember { mutableStateOf(false) }
    // 设置页
    var showSettings by remember { mutableStateOf(false) }
    // 文件选择回调（拍照/<input type=file>）
    var fileChooserCallback by remember { mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null) }
    // 下载任务（HTTP 系统下载器 + blob/data JS 桥）实时进度
    val downloads by WebDownloadCenter.tasks.collectAsState()

    fun nextId() = ++idGen
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // 文件选择结果回传
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    // ---- WebView 回调 ----
    val callbacks = remember {
        object : WebTabCallbacks {
            private fun idx(id: Long) = tabs.indexOfFirst { it.id == id }
            override fun onPageStarted(tabId: Long, url: String) {
                loadErrors.remove(tabId)
                val i = idx(tabId); if (i >= 0) tabs[i] = tabs[i].copy(url = url, isLoading = true)
            }
            override fun onPageFinished(tabId: Long, url: String, canGoBack: Boolean, canGoForward: Boolean) {
                val i = idx(tabId); if (i >= 0) tabs[i] = tabs[i].copy(
                    url = url, isLoading = false, canGoBack = canGoBack, canGoForward = canGoForward
                )
            }
            override fun onReceivedTitle(tabId: Long, title: String) {
                val i = idx(tabId); if (i >= 0 && title.isNotBlank()) tabs[i] = tabs[i].copy(title = title)
            }
            override fun onReceivedIcon(tabId: Long, icon: Bitmap?) {
                val i = idx(tabId); if (i >= 0) tabs[i] = tabs[i].copy(favicon = icon)
            }
            override fun onProgress(tabId: Long, progress: Int) {
                val i = idx(tabId); if (i >= 0) tabs[i] = tabs[i].copy(progress = progress)
            }
            override fun onExternalScheme(url: String) = openExternal(context, url)

            override fun onLoadError(tabId: Long, message: String) {
                loadErrors[tabId] = message
            }

            override fun onConsoleMessage(tabId: Long, message: String) {
                loadErrors[tabId] = message
            }

            // SSL 子资源失败会导致页面只剩背景图：弹窗让用户决定是否继续
            override fun onSslError(handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                activity?.let { act ->
                    android.app.AlertDialog.Builder(act)
                        .setTitle(R.string.web_ssl_error_title.let { act.getString(it) })
                        .setMessage(act.getString(R.string.web_ssl_error_msg, error.url ?: ""))
                        .setPositiveButton(R.string.web_ssl_continue) { _, _ -> handler.proceed() }
                        .setNegativeButton(R.string.cancel) { _, _ -> handler.cancel() }
                        .setOnCancelListener { handler.cancel() }
                        .show()
                } ?: handler.cancel()
            }

            override fun onDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
                // blob:/data: 链接交给页面内 JS 读取后经桥保存（DownloadListener 拿不到内容）
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val tabId = tabs.getOrNull(activeIndex)?.id
                    val js = "window.__ffSaveBlobUrl && window.__ffSaveBlobUrl(" +
                        "${JSONObject.quote(url)}, ${JSONObject.quote(name)})"
                    val wv = tabId?.let { webViews[it] }
                    if (wv != null) {
                        wv.evaluateJavascript(js, null)
                    } else {
                        toast(context.getString(R.string.web_download_failed))
                    }
                    return
                }
                if (WebDownloads.enqueue(context, url, userAgent, contentDisposition, mimeType) > 0) {
                    toast(context.getString(R.string.web_download_started))
                } else {
                    toast(context.getString(R.string.web_download_failed))
                }
            }

            override fun onShowFileChooser(
                cb: android.webkit.ValueCallback<Array<Uri>>?,
                params: android.webkit.WebChromeClient.FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = cb
                return try {
                    fileChooserLauncher.launch(buildFileChooserIntent(params))
                    true
                } catch (_: Exception) {
                    fileChooserCallback = null
                    toast(context.getString(R.string.web_file_picker_failed))
                    false
                }
            }

            override fun onJsAlert(url: String, message: String, result: android.webkit.JsResult): Boolean {
                showJsDialog(context, url, message, onOk = { result.confirm() }, onCancel = { result.cancel() }, showCancel = false)
                return true
            }
            override fun onJsConfirm(url: String, message: String, result: android.webkit.JsResult): Boolean {
                showJsDialog(context, url, message, onOk = { result.confirm() }, onCancel = { result.cancel() }, showCancel = true)
                return true
            }
            override fun onJsPrompt(url: String, message: String, defaultValue: String, result: android.webkit.JsPromptResult): Boolean {
                showJsPromptDialog(
                    context, url, message, defaultValue,
                    onOk = { result.confirm(it) },
                    onCancel = { result.cancel() }
                )
                return true
            }
            override fun onJsBeforeUnload(url: String, message: String, result: android.webkit.JsResult): Boolean {
                showJsDialog(context, url, message, onOk = { result.confirm() }, onCancel = { result.cancel() }, showCancel = true)
                return true
            }
            override fun onGeolocation(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                request.grant(request.resources)
            }
        }
    }

    fun webViewFor(tab: TabItem): WebView =
        webViews.getOrPut(tab.id) { createTabWebView(context, tab.id, tab.url, webSettings, callbacks) }

    fun newTab(url: String = DEFAULT_HOME, switchTo: Boolean = true) {
        val tab = TabItem(id = nextId(), url = url, title = context.getString(R.string.web_new_tab))
        tabs.add(tab)
        if (switchTo) activeIndex = tabs.lastIndex
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        webViews.remove(tab.id)?.destroy()
        tabs.removeAt(index)
        activeIndex = when {
            tabs.isEmpty() -> 0
            activeIndex >= tabs.size -> tabs.lastIndex
            index < activeIndex -> activeIndex - 1
            else -> activeIndex
        }
    }

    fun closeAll() {
        webViews.values.forEach { it.destroy() }
        webViews.clear()
        tabs.clear()
        activeIndex = 0
    }

    // 初始化恢复
    LaunchedEffect(Unit) {
        val snap = store.load()
        if (snap != null && snap.tabs.isNotEmpty()) {
            snap.tabs.forEach { (id, url) -> tabs.add(TabItem(id = id, url = url, title = snap.titles[id] ?: "")) }
            activeIndex = snap.activeIndex.coerceIn(0, tabs.lastIndex)
            if (!initialUrl.isNullOrBlank()) newTab(initialUrl)
        } else {
            newTab(initialUrl ?: DEFAULT_HOME)
        }
    }

    // 持久化
    LaunchedEffect(tabs.map { Triple(it.id, it.url, it.title) }, activeIndex) {
        if (tabs.isNotEmpty()) store.save(tabs, activeIndex) else store.clear()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViews.values.forEach { it.destroy() }
            webViews.clear()
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    LaunchedEffect(activeIndex) {
        if (tabs.isNotEmpty() && pagerState.currentPage != activeIndex) {
            pagerState.scrollToPage(activeIndex)
        }
    }

    val current = tabs.getOrNull(activeIndex)

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            current != null && webViews[current.id]?.canGoBack() == true -> webViews[current.id]?.goBack()
            else -> onClose()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 禁用左边缘手势呼出，避免与网页本身的缩放/滑动冲突；仅通过汉堡按钮开合
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                TabDrawerContent(
                    tabs = tabs,
                    activeIndex = activeIndex,
                    onSelect = { i -> activeIndex = i; scope.launch { drawerState.close() } },
                    onCloseTab = { i -> closeTab(i) },
                    onNewTab = { newTab(); scope.launch { drawerState.close() } },
                    onCloseAll = { closeAll(); scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Primary,
                            titleContentColor = Color.White,
                            navigationIconContentColor = Color.White,
                            actionIconContentColor = Color.White
                        ),
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Rounded.Menu, contentDescription = stringResource(R.string.web_tabs))
                            }
                        },
                        title = {
                            // 地址栏 = 按钮，点击弹窗输入
                            AddressButton(
                                url = current?.url ?: "",
                                onClick = { showAddressDialog = true }
                            )
                        },
                        actions = {
                            IconButton(onClick = { webViews[current?.id]?.goBack() }, enabled = current?.canGoBack == true) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.web_back))
                            }
                            IconButton(onClick = { webViews[current?.id]?.goForward() }, enabled = current?.canGoForward == true) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.web_forward))
                            }
                            IconButton(onClick = { current?.let { webViews[it.id]?.reload() } }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.web_refresh))
                            }
                            // eruda 控制台切换：未注入则加载并展开，已注入则展开/收起
                            IconButton(
                                onClick = {
                                    current?.let { tab ->
                                        webViews[tab.id]?.let { wv ->
                                            wv.evaluateJavascript(Eruda.JS_CHECK) { result ->
                                                if (result?.contains('1') == true) {
                                                    wv.evaluateJavascript(Eruda.JS_TOGGLE, null)
                                                } else {
                                                    // 加载器异步执行：延迟查询加载错误
                                                    wv.evaluateJavascript(Eruda.loaderScript(show = true), null)
                                                    wv.postDelayed({
                                                        wv.evaluateJavascript(Eruda.JS_QUERY_ERR) { err ->
                                                            val e = err?.removeSurrounding("\"")
                                                            if (e != null && e != "ok" && e != "null") {
                                                                toast(e)
                                                            }
                                                        }
                                                    }, 2500)
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = current != null
                            ) {
                                Icon(Icons.Rounded.Terminal, contentDescription = stringResource(R.string.web_console))
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.web_settings))
                            }
                        }
                    )
                    val prog = current?.progress ?: 0
                    if (current?.isLoading == true && prog in 1..99) {
                        LinearProgressIndicator(progress = { prog / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Primary)
                    } else {
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                if (tabs.isEmpty()) {
                    EmptyState(onNewTab = { newTab() })
                } else {
                    // 禁用左右滑动切标签
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = false,
                        key = { tabs[it].id }
                    ) { page ->
                        val tab = tabs.getOrNull(page)
                        if (tab != null) WebPageView(webView = webViewFor(tab), modifier = Modifier.fillMaxSize())
                    }
                }
                // 底部状态区：主文档错误 + 下载进度
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    current?.let { cur ->
                        loadErrors[cur.id]?.let { err ->
                            Text(
                                err,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.error)
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (downloads.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            downloads.takeLast(3).forEach { task ->
                                DownloadProgressRow(
                                    task = task,
                                    onDismiss = { WebDownloadCenter.clear(task.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 地址输入弹窗
    if (showAddressDialog) {
        AddressDialog(
            initialUrl = current?.url ?: "",
            onGo = { input ->
                val target = resolveInput(input)
                current?.let { webViews[it.id]?.loadUrl(target) } ?: newTab(target)
                showAddressDialog = false
            },
            onDismiss = { showAddressDialog = false }
        )
    }

    // 设置页（全屏覆盖）
    if (showSettings) {
        WebSettingsScreen(
            store = webSettings,
            onBack = {
                showSettings = false
                // 重新应用设置到所有 WebView
                webViews.values.forEach { applySettings(it, webSettings) }
                ProxyHelper.apply(context, webSettings)
            }
        )
    }
}

private const val DEFAULT_HOME = "https://www.baidu.com"

private fun resolveInput(input: String): String {
    val s = input.trim()
    if (s.isEmpty()) return DEFAULT_HOME
    return when {
        s.startsWith("http://") || s.startsWith("https://") ||
            s.startsWith("file://") || s.startsWith("about:") || s.startsWith("content://") -> s
        s.contains('.') && !s.contains(' ') -> "https://$s"
        else -> "https://www.baidu.com/s?wd=${Uri.encode(s)}"
    }
}

/** 地址栏按钮（不变形）。 */
@Composable
private fun AddressButton(url: String, onClick: () -> Unit) {
    val isSecure = url.startsWith("https://")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(
            if (isSecure) Icons.Rounded.Lock else Icons.Rounded.Public,
            contentDescription = null,
            tint = if (isSecure) Color(0xFF188038) else Color.Gray,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = url.ifBlank { stringResource(R.string.web_address_hint) },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = if (url.isBlank()) Color.Gray else Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
    }
}

/** 地址输入弹窗（输入框不变形）。 */
@Composable
private fun AddressDialog(
    initialUrl: String,
    onGo: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_address_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.web_address_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo(text) }),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onGo(text) }) { Text(stringResource(R.string.web_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** 下载任务进度条（HTTP 与 blob/data 共用）。 */
@Composable
private fun DownloadProgressRow(task: WebDownloadTask, onDismiss: () -> Unit) {
    val p = task.progress
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF2FFFFFF), RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                task.name,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            if (task.status == WebDownloadStatus.RUNNING) {
                if (p == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Primary
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Primary
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                task.status == WebDownloadStatus.FAILED -> stringResource(R.string.web_download_failed)
                task.status == WebDownloadStatus.SUCCESS -> stringResource(R.string.web_download_done)
                p != null -> stringResource(R.string.web_download_percent, (p * 100).toInt())
                else -> stringResource(R.string.web_download_running)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (task.status == WebDownloadStatus.FAILED) MaterialTheme.colorScheme.error else Color.DarkGray
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close),
                modifier = Modifier.size(14.dp),
                tint = Color.Gray
            )
        }
    }
}

/** JS prompt 弹窗：带输入框，确认时把输入内容回传页面。 */
private fun showJsPromptDialog(
    context: Context,
    url: String,
    message: String,
    defaultValue: String,
    onOk: (String) -> Unit,
    onCancel: () -> Unit
) {
    val input = android.widget.EditText(context).apply {
        setText(defaultValue)
        setSelection(text.length)
        setSingleLine()
    }
    val pad = (16 * context.resources.displayMetrics.density).toInt()
    val container = android.widget.FrameLayout(context).apply {
        setPadding(pad, pad / 2, pad, 0)
        addView(input)
    }
    android.app.AlertDialog.Builder(context)
        .setTitle(Uri.parse(url).host ?: url)
        .setMessage(message)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString()) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setOnCancelListener { onCancel() }
        .show()
}

/** JS alert/confirm 弹窗。 */
private fun showJsDialog(
    context: Context,
    url: String,
    message: String,
    onOk: () -> Unit,
    onCancel: () -> Unit,
    showCancel: Boolean
) {
    val builder = android.app.AlertDialog.Builder(context)
        .setTitle(Uri.parse(url).host ?: url)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
    if (showCancel) builder.setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
    builder.setOnCancelListener { onCancel() }
    builder.show()
}

/** 侧拉栏内容。 */
@Composable
private fun TabDrawerContent(
    tabs: List<TabItem>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(Icons.Rounded.Tab, contentDescription = null, tint = Primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.web_tab_count, tabs.size), style = MaterialTheme.typography.titleMedium, color = Primary)
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(tabs, key = { _, t -> t.id }) { index, tab ->
                TabRowItem(tab = tab, active = index == activeIndex, onClick = { onSelect(index) }, onClose = { onCloseTab(index) })
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onNewTab) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = Primary)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.web_new_tab), color = Primary)
            }
            TextButton(onClick = onCloseAll, enabled = tabs.isNotEmpty()) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.web_close_all), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TabRowItem(tab: TabItem, active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) Primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(Modifier.width(3.dp).height(32.dp).background(if (active) Primary else Color.Transparent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        if (tab.favicon != null) {
            Image(bitmap = tab.favicon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(18.dp).clip(CircleShape))
        } else {
            Icon(Icons.Rounded.Public, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(tab.title.ifBlank { stringResource(R.string.web_new_tab) }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tab.url, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.web_close_tab), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun EmptyState(onNewTab: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Tab, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.web_no_tabs), style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onNewTab) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Primary)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.web_new_tab), color = Primary)
        }
    }
}
