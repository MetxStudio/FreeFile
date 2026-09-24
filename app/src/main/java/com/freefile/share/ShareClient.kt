package com.freefile.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/** 局域网快传客户端（Sprint 5）：向其他设备的 FreeFile 服务上传/下载。 */
object ShareClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    /** 上传文件到目标设备。 */
    suspend fun upload(host: String, port: Int, file: File): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", file.name,
                        file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                    )
                    .build()
                val req = Request.Builder()
                    .url("http://$host:$port/upload")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && text.contains("\"ok\":true")) {
                        true to text
                    } else {
                        false to "HTTP ${resp.code}: $text"
                    }
                }
            } catch (e: Exception) {
                false to (e.message ?: e.toString())
            }
        }

    /** 从目标设备下载文件到 inbox。 */
    suspend fun download(host: String, port: Int, name: String, destDir: File): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("http://$host:$port/download?n=" +
                        java.net.URLEncoder.encode(name, "UTF-8"))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false to "HTTP ${resp.code}"
                    destDir.mkdirs()
                    val dest = File(destDir, name)
                    resp.body?.byteStream()?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    true to dest.absolutePath
                }
            } catch (e: Exception) {
                false to (e.message ?: e.toString())
            }
        }

    /** 取本机局域网 IPv4 地址。 */
    fun localIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress }
            .firstOrNull { it != null && it.startsWith("192.168.") || it!!.startsWith("10.") || it.startsWith("172.") }
    }.getOrNull()
}
