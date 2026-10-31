package com.freefile.tools.web

import android.content.Context
import android.content.Intent
import android.net.Proxy
import android.os.Build
import android.util.ArrayMap
import android.webkit.WebView

/**
 * WebView 代理设置。通过反射给应用内所有 WebView 设置全局代理。
 * 仅在 proxyHost 非空时生效；host 为空则清除代理。
 *
 * 注：反射系统私有 API，部分 ROM/Android 版本可能失效，失败静默忽略。
 */
object ProxyHelper {

    fun apply(context: Context, s: WebSettingsStore) {
        val host = s.proxyHost.trim()
        if (host.isEmpty()) {
            clear(context)
        } else {
            setProxy(context.applicationContext, host, s.proxyPort)
        }
    }

    fun setProxy(appContext: Context, host: String, port: Int) {
        try {
            System.setProperty("http.proxyHost", host)
            System.setProperty("http.proxyPort", port.toString())
            System.setProperty("https.proxyHost", host)
            System.setProperty("https.proxyPort", port.toString())
            setWebViewProxy(appContext, host, port)
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        try {
            System.clearProperty("http.proxyHost")
            System.clearProperty("http.proxyPort")
            System.clearProperty("https.proxyHost")
            System.clearProperty("https.proxyPort")
            setWebViewProxy(context.applicationContext, null, 0)
        } catch (_: Exception) {
        }
    }

    @Suppress("UNCHECKED_CAST", "PrivateApi", "DiscouragedPrivateApi")
    private fun setWebViewProxy(appContext: Context, host: String?, port: Int) {
        try {
            val appClass = Class.forName("android.app.Application")
            val loadedApkField = appClass.getDeclaredField("mLoadedApk").apply { isAccessible = true }
            val loadedApk = loadedApkField.get(appContext)
            val loadedApkClass = Class.forName("android.app.LoadedApk")
            val receiversField = loadedApkClass.getDeclaredField("mReceivers").apply { isAccessible = true }
            val receivers = receiversField.get(loadedApk) as ArrayMap<*, *>

            receivers.values.forEach { map ->
                (map as ArrayMap<*, *>).keys.forEach { clazz ->
                    if (clazz is Class<*> && clazz.name.contains("ProxyChangeListener")) {
                        val onReceive = clazz.getDeclaredMethod("onReceive", Context::class.java, Intent::class.java)
                        val intent = Intent(Proxy.PROXY_CHANGE_ACTION)
                        val proxyInfoClass = Class.forName("android.net.ProxyInfo")
                        val buildDirect = proxyInfoClass.getDeclaredMethod(
                            "buildDirectProxy", String::class.java, Int::class.javaPrimitiveType
                        )
                        intent.putExtra("proxy", buildDirect.invoke(null, host, port) as android.os.Parcelable)
                        onReceive.invoke(clazz.getDeclaredConstructor().newInstance(), appContext, intent)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
