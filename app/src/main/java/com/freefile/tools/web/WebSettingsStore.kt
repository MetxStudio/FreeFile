package com.freefile.tools.web

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebSettings

/**
 * 浏览器 WebView 设置持久化（SharedPreferences）。
 * 提供几十项 WebSettings API 级开关 + 用户代理 + 代理，支持一键复位。
 */
class WebSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("web_viewer_settings", Context.MODE_PRIVATE)

    // ---- 读取（缺省值 = WebView 系统默认） ----
    var javaScriptEnabled: Boolean
        get() = prefs.getBoolean(K.JS, true)
        set(v) = put(K.JS, v)
    var domStorageEnabled: Boolean
        get() = prefs.getBoolean(K.DOM, true)
        set(v) = put(K.DOM, v)
    var databaseEnabled: Boolean
        get() = prefs.getBoolean(K.DB, true)
        set(v) = put(K.DB, v)
    var supportZoom: Boolean
        get() = prefs.getBoolean(K.ZOOM, true)
        set(v) = put(K.ZOOM, v)
    var builtInZoomControls: Boolean
        get() = prefs.getBoolean(K.ZOOM_CTRL, true)
        set(v) = put(K.ZOOM_CTRL, v)
    var displayZoomControls: Boolean
        get() = prefs.getBoolean(K.ZOOM_BTN, false)
        set(v) = put(K.ZOOM_BTN, v)
    var useWideViewPort: Boolean
        get() = prefs.getBoolean(K.WIDE, true)
        set(v) = put(K.WIDE, v)
    var loadWithOverviewMode: Boolean
        get() = prefs.getBoolean(K.OVERVIEW, true)
        set(v) = put(K.OVERVIEW, v)
    var javaScriptCanOpenWindowsAutomatically: Boolean
        get() = prefs.getBoolean(K.JS_WIN, true)
        set(v) = put(K.JS_WIN, v)
    var supportMultipleWindows: Boolean
        get() = prefs.getBoolean(K.MULTI_WIN, false)
        set(v) = put(K.MULTI_WIN, v)
    var allowFileAccess: Boolean
        get() = prefs.getBoolean(K.FILE, true)
        set(v) = put(K.FILE, v)
    var allowContentAccess: Boolean
        get() = prefs.getBoolean(K.CONTENT, true)
        set(v) = put(K.CONTENT, v)
    var allowFileAccessFromFileURLs: Boolean
        get() = prefs.getBoolean(K.FILE_URL, false)
        set(v) = put(K.FILE_URL, v)
    var allowUniversalAccessFromFileURLs: Boolean
        get() = prefs.getBoolean(K.UNIVERSAL, false)
        set(v) = put(K.UNIVERSAL, v)
    var mediaPlaybackRequiresUserGesture: Boolean
        get() = prefs.getBoolean(K.MEDIA_GESTURE, false)
        set(v) = put(K.MEDIA_GESTURE, v)
    var blockNetworkImage: Boolean
        get() = prefs.getBoolean(K.BLOCK_IMG, false)
        set(v) = put(K.BLOCK_IMG, v)
    var blockNetworkLoads: Boolean
        get() = prefs.getBoolean(K.BLOCK_NET, false)
        set(v) = put(K.BLOCK_NET, v)
    var loadsImagesAutomatically: Boolean
        get() = prefs.getBoolean(K.AUTO_IMG, true)
        set(v) = put(K.AUTO_IMG, v)
    var safeBrowsingEnabled: Boolean
        get() = prefs.getBoolean(K.SAFE_BROWSE, true)
        set(v) = put(K.SAFE_BROWSE, v)
    var saveFormData: Boolean
        get() = prefs.getBoolean(K.FORM, true)
        set(v) = put(K.FORM, v)
    var textZoom: Int
        get() = prefs.getInt(K.TEXT_ZOOM, 100)
        set(v) = put(K.TEXT_ZOOM, v)
    var minimumFontSize: Int
        get() = prefs.getInt(K.MIN_FONT, 8)
        set(v) = put(K.MIN_FONT, v)
    var minimumLogicalFontSize: Int
        get() = prefs.getInt(K.MIN_LFONT, 8)
        set(v) = put(K.MIN_LFONT, v)
    var defaultFontSize: Int
        get() = prefs.getInt(K.DEF_FONT, 16)
        set(v) = put(K.DEF_FONT, v)
    var defaultFixedFontSize: Int
        get() = prefs.getInt(K.DEF_FFONT, 13)
        set(v) = put(K.DEF_FFONT, v)
    var standardFontFamily: String
        get() = prefs.getString(K.FONT_FAMILY, "sans-serif") ?: "sans-serif"
        set(v) = put(K.FONT_FAMILY, v)
    var defaultTextEncodingName: String
        get() = prefs.getString(K.ENCODING, "UTF-8") ?: "UTF-8"
        set(v) = put(K.ENCODING, v)

    /** 缓存模式（WebSettings.LOAD_*） */
    var cacheMode: Int
        get() = prefs.getInt(K.CACHE, WebSettings.LOAD_DEFAULT)
        set(v) = put(K.CACHE, v)

    /** 混合内容（WebSettings.MIXED_CONTENT_*） */
    var mixedContentMode: Int
        get() = prefs.getInt(K.MIXED, WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE)
        set(v) = put(K.MIXED, v)

    /** 用户代理：空 = WebView 默认 */
    var userAgent: String
        get() = prefs.getString(K.UA, "") ?: ""
        set(v) = put(K.UA, v)

    /** eruda 控制台：页面加载完成自动注入入口按钮 */
    var erudaEnabled: Boolean
        get() = prefs.getBoolean(K.ERUDA, false)
        set(v) = put(K.ERUDA, v)

    /** 代理 host，空 = 不用代理 */
    var proxyHost: String
        get() = prefs.getString(K.PROXY_HOST, "") ?: ""
        set(v) = put(K.PROXY_HOST, v)
    var proxyPort: Int
        get() = prefs.getInt(K.PROXY_PORT, 8080)
        set(v) = put(K.PROXY_PORT, v)

    private fun put(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
    private fun put(k: String, v: Int) = prefs.edit().putInt(k, v).apply()
    private fun put(k: String, v: String) = prefs.edit().putString(k, v).apply()

    /** 一键复位：清空全部，回到系统默认。 */
    fun resetAll() = prefs.edit().clear().apply()

    private object K {
        const val JS = "js"; const val DOM = "dom"; const val DB = "db"
        const val ZOOM = "zoom"; const val ZOOM_CTRL = "zoomCtrl"; const val ZOOM_BTN = "zoomBtn"
        const val WIDE = "wide"; const val OVERVIEW = "overview"
        const val JS_WIN = "jsWin"; const val MULTI_WIN = "multiWin"
        const val FILE = "file"; const val CONTENT = "content"
        const val FILE_URL = "fileUrl"; const val UNIVERSAL = "universal"
        const val MEDIA_GESTURE = "mediaGesture"
        const val BLOCK_IMG = "blockImg"; const val BLOCK_NET = "blockNet"; const val AUTO_IMG = "autoImg"
        const val SAFE_BROWSE = "safeBrowse"; const val FORM = "form"
        const val TEXT_ZOOM = "textZoom"; const val MIN_FONT = "minFont"; const val MIN_LFONT = "minLFont"
        const val DEF_FONT = "defFont"; const val DEF_FFONT = "defFFont"
        const val FONT_FAMILY = "fontFamily"; const val ENCODING = "encoding"
        const val CACHE = "cache"; const val MIXED = "mixed"; const val UA = "ua"
        const val ERUDA = "eruda"
        const val PROXY_HOST = "proxyHost"; const val PROXY_PORT = "proxyPort"
    }
}
