package com.freefile.tools.web

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import java.io.File
import java.io.RandomAccessFile

/**
 * DOCX 查看器本地桥。
 *
 * 页面仍然通过 file:///android_asset 加载（与普通本地 HTML 一致），
 * docx 原始字节不再依赖 fetch/XHR（file 协议不支持 fetch，虚拟域名拦截
 * 又容易受 WebView 版本影响），而是通过 [DocxBridge] 由 Kotlin 直接分块
 * 读出并交给页面，保证任何机型都能拿到完整文件。
 */
object LocalWebServer {

    private const val ASSET_BASE = "file:///android_asset"

    const val DOCX_VIEWER_URL =
        "$ASSET_BASE/web-plugin/docx-viewer/docx-viewer-index.html"

    private const val DOCX_SRC_PREFIX = "/docx/"

    private const val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** 兼容旧页面里的 CDN 引用：离线时改用内置的 mammoth。 */
    private const val MAMMOTH_CDN = "https://cdn.jsdelivr.net/npm/mammoth"

    /** DOCX 查看器页面地址（携带文件标识、名称、大小）。 */
    fun docxViewerUrl(file: File): String {
        val key = encodePath(file.absolutePath)
        return buildString {
            append(DOCX_VIEWER_URL)
            append("?src=").append(Uri.encode("$DOCX_SRC_PREFIX$key"))
            append("&name=").append(Uri.encode(file.name))
            append("&size=").append(file.length())
        }
    }

    /** 从 docx 查看器 URL 解析出待查看文件；不是该页面或无文件时返回 null。 */
    fun docxFileFromUrl(url: String?): File? {
        if (url == null || !url.startsWith(DOCX_VIEWER_URL)) return null
        val src = try {
            Uri.parse(url).getQueryParameter("src")
        } catch (_: Throwable) {
            null
        } ?: return null
        if (!src.startsWith(DOCX_SRC_PREFIX)) return null
        return try {
            File(decodePath(src.removePrefix(DOCX_SRC_PREFIX))).takeIf { it.isFile }
        } catch (_: Throwable) {
            null
        }
    }

    /** 拦截解析库 CDN 请求，返回内置文件；其他 URL 返回 null。 */
    fun intercept(context: Context, url: String): WebResourceResponse? {
        if (!url.startsWith(MAMMOTH_CDN)) return null
        return try {
            WebResourceResponse(
                "application/javascript", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-cache"),
                context.assets.open("web-plugin/docx-viewer/mammoth.browser.min.js")
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun encodePath(path: String): String =
        Base64.encodeToString(
            path.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

    private fun decodePath(key: String): String =
        String(
            Base64.decode(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8
        )
}

/**
 * docx 文件读取桥（只暴露构造时传入的单个文件）。
 *
 * 页面按 256KB 分块调用 [readBase64]，避免一次性构造超大字符串导致
 * 传输截断；拼接后交给 mammoth 解析。
 */
class DocxBridge(private val file: File) {

    private val lock = Any()

    @JavascriptInterface
    fun size(): Long = file.length()

    @JavascriptInterface
    fun readBase64(offset: Long, length: Int): String {
        if (length <= 0 || offset < 0) return ""
        synchronized(lock) {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    val remain = file.length() - offset
                    if (remain <= 0) return ""
                    val max = minOf(length.toLong(), remain).toInt()
                    val buffer = ByteArray(max)
                    var read = 0
                    while (read < max) {
                        val n = raf.read(buffer, read, max - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read <= 0) return ""
                    return Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                }
            } catch (_: Throwable) {
                return ""
            }
        }
    }
}
