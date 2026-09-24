package com.freefile.vfs.remote

import com.freefile.data.db.NetworkLocation
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端（Sprint 5）：PROPFIND/MKCOL/DELETE/MOVE/COPY/GET/PUT。
 * 基于 OkHttp（支持任意 HTTP method），XML 解析用系统内置 XmlPullParser。
 */
class WebDavClient(private val loc: NetworkLocation) : RemoteClient {

    private val scheme = if (loc.useSsl) "https" else "http"
    private val base = "$scheme://${loc.host}:${loc.port}${loc.basePath.trimEnd('/')}"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .authenticator { _, response ->
            response.request.newBuilder()
                .header("Authorization", Credentials.basic(loc.username, loc.password))
                .build()
        }
        .build()

    private fun url(path: String): String =
        base + path.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) "" else java.net.URLEncoder.encode(segment, "UTF-8")
                .replace("+", "%20")
        }

    private fun authRequest(): Request.Builder = Request.Builder()
        .header("Authorization", Credentials.basic(loc.username, loc.password))

    private fun bodyOf(response: okhttp3.Response): String =
        response.body?.string().orEmpty()

    // ---------- 列目录（PROPFIND Depth:1） ----------

    override suspend fun list(dir: String): List<VfsEntry> = withContext(Dispatchers.IO) {
        val req = authRequest()
            .url(url(dir) + if (dir == "/") "/" else "/")
            .header("Depth", "1")
            .method("PROPFIND", null as RequestBody?)
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw VfsException("目录不存在: $dir")
            if (!resp.isSuccessful) throw VfsException("WebDAV PROPFIND 失败(${resp.code})")
            val xml = bodyOf(resp)
            val selfPath = RemoteEngine.locationPath(loc.id, dir)
            parseMultistatus(xml, dir)
                .filter { it.path.trimEnd('/') != selfPath.trimEnd('/') }
        }
    }

    private data class HrefItem(val href: String, val isDir: Boolean, val size: Long, val modified: Long)

    private fun parseMultistatus(xml: String, dir: String): List<VfsEntry> {
        val out = ArrayList<VfsEntry>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(java.io.StringReader(xml))
        var inResp = false
        var inCollection = false
        var href = ""
        var size = 0L
        var modified = 0L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    when {
                        name == "response" || name == "d:response" || name == "ms:response" -> {
                            inResp = true; href = ""; size = 0; modified = 0; inCollection = false
                        }
                        inResp && name.endsWith("href") -> href = parser.nextText().trim()
                        inResp && name.endsWith("collection") -> inCollection = true
                        inResp && name.endsWith("getcontentlength") -> size = parser.nextText().trim().toLongOrNull() ?: 0
                        inResp && name.endsWith("getlastmodified") -> modified = parseHttpDate(parser.nextText().trim())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase().endsWith("response")) {
                        inResp = false
                        if (href.isNotEmpty()) out.add(toEntry(href, inCollection, size, modified))
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseHttpDate(text: String): Long = try {
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        fmt.parse(text)?.time ?: 0
    } catch (_: Exception) { 0 }

    /** href → 位置内子路径（兼容服务器返回完整 URL 或带前缀路径两种形式）。 */
    private fun hrefToSub(href: String): String {
        var h = try {
            java.net.URLDecoder.decode(href, "UTF-8")
        } catch (_: Exception) { href }
        if (h.contains("://")) h = h.substringAfter("://").substringAfter('/', h)
        val prefix = loc.basePath.trimEnd('/')
        if (prefix.isNotEmpty() && h.startsWith(prefix)) h = h.removePrefix(prefix)
        return h.ifEmpty { "/" }
    }

    private fun normalize(href: String): String = hrefToSub(href).trimEnd('/')

    private fun toEntry(href: String, isDir: Boolean, size: Long, modified: Long): VfsEntry {
        val sub = hrefToSub(href)
        val name = sub.trimEnd('/').substringAfterLast('/')
        return VfsEntry(
            path = RemoteEngine.locationPath(loc.id, sub),
            name = name,
            isDirectory = isDir || href.endsWith("/"),
            size = if (isDir) 0 else size,
            lastModified = modified,
            isHidden = name.startsWith(".")
        )
    }

    // ---------- stat（PROPFIND Depth:0） ----------

    override suspend fun stat(path: String): VfsEntry? = withContext(Dispatchers.IO) {
        val req = authRequest()
            .url(url(path))
            .header("Depth", "0")
            .method("PROPFIND", null as RequestBody?)
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val items = parseMultistatus(bodyOf(resp), path)
                items.firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 增删改 ----------

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        val req = authRequest().url(url(path)).method("MKCOL", null as RequestBody?).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 405) throw VfsException("MKCOL 失败(${resp.code})")
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        val req = authRequest().url(url(path)).method("DELETE", null as RequestBody?).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw VfsException("DELETE 失败(${resp.code})")
        }
    }

    override suspend fun rename(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        val req = authRequest()
            .url(url(oldPath))
            .header("Destination", url(newPath))
            .header("Overwrite", "F")
            .method("MOVE", null as RequestBody?)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // MOVE 失败 → 尝试 COPY+DELETE
                val copy = authRequest()
                    .url(url(oldPath))
                    .header("Destination", url(newPath))
                    .header("Overwrite", "F")
                    .method("COPY", null as RequestBody?)
                    .build()
                http.newCall(copy).execute().use { c ->
                    if (!c.isSuccessful) throw VfsException("重命名失败(${resp.code})")
                }
                delete(oldPath, recursive = true)
            }
        }
    }

    // ---------- 读写 ----------

    override fun read(path: String): InputStream {
        val req = authRequest().url(url(path)).build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw VfsException("GET 失败(${resp.code})")
        }
        return resp.body?.byteStream() ?: throw VfsException("响应体为空")
    }

    override fun write(path: String): OutputStream {
        // WebDAV PUT 需要一次性提供 body：用管道流桥接，写入端阻塞直到 PUT 完成
        val pipedOut = java.io.PipedOutputStream()
        val pipedIn = java.io.PipedInputStream(pipedOut, 1 shl 16)
        val body: RequestBody = object : RequestBody() {
            override fun contentType(): okhttp3.MediaType? =
                "application/octet-stream".toMediaTypeOrNull()
            override fun writeTo(sink: okio.BufferedSink) {
                val buf = ByteArray(8192)
                while (true) {
                    val n = pipedIn.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                }
            }
        }
        Thread {
            val req = authRequest().url(url(path)).put(body).build()
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw VfsException("PUT 失败(${resp.code})")
                }
            } catch (e: Exception) {
                try { pipedOut.close() } catch (_: Exception) {}
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()
        return pipedOut
    }

    override fun close() {}
}
