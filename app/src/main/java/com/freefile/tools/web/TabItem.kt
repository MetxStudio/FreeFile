package com.freefile.tools.web

import android.graphics.Bitmap
import android.webkit.WebView

/**
 * 单个标签页数据。WebView 实例由页面持有并复用（不参与持久化）。
 */
data class TabItem(
    val id: Long,
    val url: String,
    val title: String = "",
    val favicon: Bitmap? = null,
    /** 页面加载进度 0..100 */
    val progress: Int = 0,
    /** 是否可后退（WebView.canGoBack） */
    val canGoBack: Boolean = false,
    /** 是否可前进（WebView.canGoForward） */
    val canGoForward: Boolean = false,
    /** 是否正在加载 */
    val isLoading: Boolean = false
)
