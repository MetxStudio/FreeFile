package com.freefile.tools.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * eruda 移动端控制台。
 *
 * 加载方式：注入一小段脚本创建 <script src=CDN> 标签，CDN 请求被
 * shouldInterceptRequest 拦截并返回本地 assets 内容 —— 实测 evaluateJavascript
 * 内联执行 ~500KB 源码在部分 WebView 上会因传输截断直接 SyntaxError，
 * 而 script 标签方式由 WebView 自身加载管线处理，稳定可靠。
 * 注入时机为 onPageStarted（而非 onPageFinished），保证 Network/Sources 捕获完整。
 */
object Eruda {
    private const val CDN_PREFIX = "https://cdn.jsdelivr.net/npm/eruda"

    /**
     * 加载器（仅数百字节，传输安全）：创建 script 标签，加载完成后 init，可选 show。
     * 异步加载，结果写入 window.__eruda_inited / __eruda_err 供后续查询。
     */
    fun loaderScript(show: Boolean): String = """
        (function(){
          if(window.__eruda_inited)return 'inited';
          if(window.__eruda_loading)return 'loading';
          window.__eruda_loading=true;
          function done(){
            try{
              if(!window.eruda){window.__eruda_loading=false;window.__eruda_err='ERR:eruda missing after load';return;}
              if(!window.__eruda_inited){eruda.init();window.__eruda_inited=true;}
              SHOW_PLACEHOLDER
              window.__eruda_loading=false;window.__eruda_err=null;
            }catch(e){window.__eruda_loading=false;window.__eruda_err='ERR:'+((e&&e.message)||String(e));}
          }
          function attach(){
            var root=document.head||document.documentElement;
            if(!root){setTimeout(attach,20);return;}
            var s=document.createElement('script');
            s.src='CDN_URL_PLACEHOLDER';
            s.onload=done;
            s.onerror=function(){window.__eruda_loading=false;window.__eruda_err='ERR:script load failed';};
            root.appendChild(s);
          }
          attach();
          return 'injecting';
        })();
    """.trimIndent()
        .replace("SHOW_PLACEHOLDER", if (show) "eruda.show();window.__eruda_visible=true;" else "")
        .replace("CDN_URL_PLACEHOLDER", CDN_PREFIX)

    /** 已注入时的展开/收起切换 */
    const val JS_TOGGLE =
        "(function(){" +
            "if(!window.eruda||!window.__eruda_inited)return 'not_inited';" +
            "if(window.__eruda_visible){eruda.hide();window.__eruda_visible=false;return 'hidden';}" +
            "eruda.show();window.__eruda_visible=true;return 'shown';})()"

    /** 检测当前页是否已注入 */
    const val JS_CHECK = "(window.__eruda_inited===true)?'1':'0'"

    /** 查询加载错误 */
    const val JS_QUERY_ERR = "window.__eruda_err||'ok'"

    /** 拦截 eruda CDN 请求，用本地 assets 提供内容（离线可用） */
    fun intercept(context: Context, url: String): WebResourceResponse? {
        if (!url.startsWith(CDN_PREFIX)) return null
        return try {
            WebResourceResponse(
                "application/javascript", "utf-8",
                context.assets.open("eruda.min.js")
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 单个标签页的 WebView 创建：应用 [WebSettingsStore] 中所有设置，
 * 并绑定页面回调（标题/favicon/进度/历史状态）。
 *
 * 文件选择/下载/JS 弹窗等交互回调由 Screen 通过 [WebTabCallbacks] 提供。
 */
@SuppressLint("SetJavaScriptEnabled")
fun createTabWebView(
    context: Context,
    tabId: Long,
    initialUrl: String,
    settings: WebSettingsStore,
    callbacks: WebTabCallbacks
): WebView {
    return WebView(context).apply {
        applySettings(this, settings)

        // Cookie：bing 等站点的重定向链依赖 Cookie 会话
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        // blob:/data: 下载保存桥（页面脚本生成文件时使用）
        addJavascriptInterface(WebDownloadBridge(context), "FreeFileDownload")

        // docx 查看页：由 Kotlin 直接分块提供文件字节，避免 file 协议 fetch 限制
        LocalWebServer.docxFileFromUrl(initialUrl)?.let { file ->
            addJavascriptInterface(DocxBridge(file), "FreeFileDocx")
        }

        // 渲染与滚动优化：显式硬件层，提升滑动/缩放流畅度
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme?.lowercase() ?: ""
                return when (scheme) {
                    "http", "https", "file", "about", "data", "javascript", "content" -> false
                    // 电话/邮件/短信/应用内跳转交给系统
                    else -> {
                        callbacks.onExternalScheme(url)
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                callbacks.onPageStarted(tabId, url ?: "")
                // 尽早注入 eruda 加载器：页面自身脚本运行前发起加载，
                // Network/Sources 才能捕获并解析尽量多的资源
                if (settings.erudaEnabled &&
                    (url?.startsWith("http://") == true || url?.startsWith("https://") == true)
                ) {
                    view.evaluateJavascript(Eruda.loaderScript(show = false), null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // 注入 blob:/data: 下载钩子（页面脚本生成文件时也能保存）
                if (url?.startsWith("javascript:") != true) {
                    view.evaluateJavascript(WebDownloads.HOOK_JS, null)
                }
                callbacks.onPageFinished(
                    tabId, url ?: "", view.canGoBack(), view.canGoForward()
                )
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // eruda CDN → 本地 assets
                Eruda.intercept(view.context, request.url.toString())?.let { return it }
                // 解析库 CDN → 本地内置 mammoth（离线兜底）
                LocalWebServer.intercept(view.context, request.url.toString())?.let { return it }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                // 仅上报主文档错误，子资源错误不打断
                if (request.isForMainFrame) {
                    callbacks.onLoadError(
                        tabId,
                        error.description?.toString() ?: "error ${error.errorCode}"
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    callbacks.onLoadError(tabId, "HTTP ${errorResponse.statusCode}")
                }
            }

            // SSL 错误：默认取消（安全），交由回调让用户决定
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                callbacks.onSslError(handler, error)
            }

            // 下载交给系统下载器
            init {
                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    callbacks.onDownload(url, userAgent, contentDisposition, mimeType)
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                callbacks.onReceivedTitle(tabId, title ?: "")
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                callbacks.onReceivedIcon(tabId, icon)
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                callbacks.onProgress(tabId, newProgress)
            }

            // <input type=file> / 拍照
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                return callbacks.onShowFileChooser(filePathCallback, fileChooserParams)
            }

            // JS alert/confirm/prompt
            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                return callbacks.onJsAlert(url, message, result)
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                return callbacks.onJsConfirm(url, message, result)
            }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: android.webkit.JsPromptResult): Boolean {
                return callbacks.onJsPrompt(url, message, defaultValue, result)
            }

            // 页面离开前的 JS 确认消息
            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                return callbacks.onJsBeforeUnload(url, message, result)
            }

            // 地理位置授权
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                callbacks.onGeolocation(origin, callback)
            }

            // 权限请求（摄像头/麦克风/拍照）
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                callbacks.onPermissionRequest(request)
            }
        }

        if (initialUrl.isNotEmpty()) loadUrl(initialUrl)
    }
}

/** 把持久化的设置应用到 WebView。 */
@SuppressLint("SetJavaScriptEnabled")
fun applySettings(webView: WebView, s: WebSettingsStore) {
    webView.settings.apply {
        javaScriptEnabled = s.javaScriptEnabled
        domStorageEnabled = s.domStorageEnabled
        databaseEnabled = s.databaseEnabled
        setSupportZoom(s.supportZoom)
        builtInZoomControls = s.builtInZoomControls
        displayZoomControls = s.displayZoomControls
        useWideViewPort = s.useWideViewPort
        loadWithOverviewMode = s.loadWithOverviewMode
        javaScriptCanOpenWindowsAutomatically = s.javaScriptCanOpenWindowsAutomatically
        setSupportMultipleWindows(s.supportMultipleWindows)
        allowFileAccess = s.allowFileAccess
        allowContentAccess = s.allowContentAccess
        allowFileAccessFromFileURLs = s.allowFileAccessFromFileURLs
        allowUniversalAccessFromFileURLs = s.allowUniversalAccessFromFileURLs
        mediaPlaybackRequiresUserGesture = s.mediaPlaybackRequiresUserGesture
        blockNetworkImage = s.blockNetworkImage
        blockNetworkLoads = s.blockNetworkLoads
        loadsImagesAutomatically = s.loadsImagesAutomatically
        safeBrowsingEnabled = s.safeBrowsingEnabled
        @Suppress("DEPRECATION")
        saveFormData = s.saveFormData
        textZoom = s.textZoom
        minimumFontSize = s.minimumFontSize
        minimumLogicalFontSize = s.minimumLogicalFontSize
        defaultFontSize = s.defaultFontSize
        defaultFixedFontSize = s.defaultFixedFontSize
        standardFontFamily = s.standardFontFamily
        defaultTextEncodingName = s.defaultTextEncodingName
        cacheMode = s.cacheMode
        mixedContentMode = s.mixedContentMode
        if (s.userAgent.isNotBlank()) userAgentString = s.userAgent
    }
}

/** WebView → Compose/Activity 交互回调集合。 */
interface WebTabCallbacks {
    fun onPageStarted(tabId: Long, url: String)
    fun onPageFinished(tabId: Long, url: String, canGoBack: Boolean, canGoForward: Boolean)
    fun onReceivedTitle(tabId: Long, title: String)
    fun onReceivedIcon(tabId: Long, icon: Bitmap?)
    fun onProgress(tabId: Long, progress: Int)
    fun onExternalScheme(url: String)

    // ---- 交互类回调（有默认实现，方便按需覆盖） ----
    fun onLoadError(tabId: Long, message: String) {}
    fun onConsoleMessage(tabId: Long, message: String) {}
    fun onDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {}
    fun onSslError(handler: SslErrorHandler, error: SslError) { handler.cancel() }
    fun onShowFileChooser(
        cb: android.webkit.ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ): Boolean = false
    fun onJsAlert(url: String, message: String, result: android.webkit.JsResult): Boolean = false
    fun onJsConfirm(url: String, message: String, result: android.webkit.JsResult): Boolean = false
    fun onJsPrompt(url: String, message: String, defaultValue: String, result: android.webkit.JsPromptResult): Boolean = false
    fun onJsBeforeUnload(url: String, message: String, result: android.webkit.JsResult): Boolean = false
    fun onGeolocation(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }
    fun onPermissionRequest(request: android.webkit.PermissionRequest) { request.deny() }
}

/** Compose 渲染一个已存在的 WebView。 */
@androidx.compose.runtime.Composable
fun WebPageView(
    webView: WebView,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    androidx.compose.ui.viewinterop.AndroidView(factory = { webView }, modifier = modifier)
}

/** 外部 scheme（电话/邮件/应用）尝试系统打开。 */
fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
}

/** 扩展名 → MIME。部分系统 MimeTypeMap 缺少 Office 类型，这里补齐。 */
private val EXTENSION_MIME = mapOf(
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "doc" to "application/msword",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "xls" to "application/vnd.ms-excel",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "ppt" to "application/vnd.ms-powerpoint",
    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "txt" to "text/plain"
)

/**
 * 构造 `<input type=file>` 的系统选择器 Intent。
 *
 * 不能直接用 FileChooserParams.createIntent()：当 accept 只写了扩展名
 * （如 docx 查看器的 `.docx`）时，它会把 ".docx" 当作 MIME type，导致
 * 选择器没有可选项。这里把扩展名翻译成 MIME，并在多类型时用
 * EXTRA_MIME_TYPES 传全部候选。
 */
fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
    val mimes = LinkedHashSet<String>()

    params?.acceptTypes?.forEach { raw ->
        val a = raw.trim()
        when {
            a.isEmpty() -> Unit
            a.startsWith(".") -> {
                val ext = a.removePrefix(".").lowercase()
                val mime = EXTENSION_MIME[ext]
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                if (mime != null) mimes.add(mime)
            }
            a.contains("/") -> mimes.add(a)
        }
    }

    // accept 为空或包含 */* 时放开全部类型：任何文件都能上传
    val anyType = mimes.isEmpty() || mimes.contains("*/*")
    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = if (anyType) "*/*" else if (mimes.size == 1) mimes.first() else "*/*"
        if (!anyType && mimes.size > 1) {
            putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
        }
        if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }
    return Intent.createChooser(intent, null)
}