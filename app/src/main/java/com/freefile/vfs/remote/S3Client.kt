package com.freefile.vfs.remote

import com.freefile.data.db.NetworkLocation
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3 客户端（Sprint 5）：手写 AWS SigV4 签名 + OkHttp。
 * 兼容所有 S3 端点（AWS / 阿里 OSS 的 S3 兼容域名 / MinIO 等）。
 * loc.basePath = bucket；extras: region（默认 us-east-1）、endpoint（覆盖 host，可选）、prefix（根前缀，可选）。
 */
class S3Client(private val loc: NetworkLocation) : RemoteClient {

    private val bucket = loc.basePath
    private val region = loc.extrasValue("region").ifEmpty { "us-east-1" }
    private val rootPrefix = loc.extrasValue("prefix").trim('/')
    private val scheme = if (loc.useSsl) "https" else "http"
    private val endpointHost = loc.extrasValue("endpoint").ifEmpty { loc.host }
    private val virtualHost = "$bucket.$endpointHost"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // ---------- SigV4 签名 ----------

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    /** 构造带 SigV4 签名的请求。 */
    private fun sign(
        method: String,
        key: String,
        query: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        bodyStream: RequestBody? = null,
        copySource: String? = null
    ): Request {
        val now = java.util.Date()
        val amzDate = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(now)
        val dateStamp = amzDate.substring(0, 8)
        val payloadHash = if (bodyStream != null) "UNSIGNED-PAYLOAD" else sha256Hex(body ?: ByteArray(0))

        // 规范 URI 与查询
        val canonicalUri = "/" + key.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val canonicalQuery = query.entries.sortedBy { it.key }.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8").replace("+", "%20") + "=" +
                URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        }

        val hostHeader = virtualHost
        val headers = sortedMapOf(
            "host" to hostHeader,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate
        )
        copySource?.let { headers["x-amz-copy-source"] = it }

        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }

        val canonicalRequest = listOf(
            method, canonicalUri, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash
        ).joinToString("\n")

        val scope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonicalRequest.toByteArray())
        ).joinToString("\n")

        val kDate = hmac("AWS4${loc.password}".toByteArray(), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, "s3")
        val kSigning = hmac(kService, "aws4_request")
        val signature = hmac(kSigning, stringToSign).joinToString("") { "%02x".format(it) }

        val builder = Request.Builder()
            .url("$scheme://$hostHeader$canonicalUri" + if (canonicalQuery.isEmpty()) "" else "?$canonicalQuery")
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=${loc.username}/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
            )
        copySource?.let { builder.header("x-amz-copy-source", it) }
        when {
            bodyStream != null -> builder.put(bodyStream)
            body != null -> builder.put(body.toRequestBody(null))
            else -> builder.method(method, null)
        }
        return builder.build()
    }

    // ---------- 路径 ----------

    /** 位置内子路径 → S3 key（含 rootPrefix）。 */
    private fun keyOf(path: String): String {
        val sub = path.trimStart('/')
        return if (rootPrefix.isEmpty()) sub else "$rootPrefix/$sub".replace("//", "/")
    }

    private fun subOfKey(key: String): String =
        if (rootPrefix.isEmpty()) "/$key"
        else key.removePrefix(rootPrefix).let { if (it.startsWith("/")) it else "/$it" }

    private fun displayName(subPath: String): String =
        subPath.trimEnd('/').substringAfterLast('/')

    // ---------- 列目录（ListObjectsV2，delimiter 模拟目录） ----------

    override suspend fun list(dir: String): List<VfsEntry> = withContext(Dispatchers.IO) {
        val dirPrefix = (keyOf(dir).trimEnd('/') + "/").let { if (it == "/") rootPrefix + "/" else it }
        val query = mapOf(
            "list-type" to "2",
            "prefix" to dirPrefix,
            "delimiter" to "/",
            "max-keys" to "1000"
        )
        val req = sign("GET", keyOf(dir).trimEnd('/') + "/", query)
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw VfsException("S3 列目录失败(${resp.code})")
            parseList(resp.body?.string().orEmpty(), dir)
        }
    }

    private fun parseList(xml: String, dir: String): List<VfsEntry> {
        val out = ArrayList<VfsEntry>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(java.io.StringReader(xml))
        var event = parser.eventType
        var insideContents = false
        var insideCp = false
        var key = ""
        var size = 0L
        var modified = 0L
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Contents" -> { insideContents = true; key = ""; size = 0; modified = 0 }
                    "CommonPrefixes" -> insideCp = true
                    "Key" -> if (insideContents || insideCp) key = parser.nextText()
                    "Size" -> if (insideContents) size = parser.nextText().toLongOrNull() ?: 0
                    "LastModified" -> if (insideContents) modified = parser.nextText().let {
                        try {
                            val fmt = java.text.SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US
                            )
                            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            fmt.parse(it.substringBefore('.'))?.time ?: 0
                        } catch (_: Exception) { 0 }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Contents" -> {
                        insideContents = false
                        // 排除目录标记对象自身（dir/）
                        val sub = subOfKey(key)
                        if (key != keyOf(dir).trimEnd('/') + "/") {
                            out.add(
                                VfsEntry(
                                    path = RemoteEngine.locationPath(loc.id, sub),
                                    name = displayName(sub),
                                    isDirectory = false,
                                    size = size,
                                    lastModified = modified
                                )
                            )
                        }
                    }
                    "CommonPrefixes" -> {
                        insideCp = false
                        val sub = subOfKey(key)
                        out.add(
                            VfsEntry(
                                path = RemoteEngine.locationPath(loc.id, sub),
                                name = displayName(sub),
                                isDirectory = true
                            )
                        )
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    // ---------- stat（HEAD） ----------

    override suspend fun stat(path: String): VfsEntry? = withContext(Dispatchers.IO) {
        val key = keyOf(path)
        if (key.isEmpty() || key == "/") {
            return@withContext VfsEntry(
                path = RemoteEngine.locationPath(loc.id, "/"),
                name = loc.name, isDirectory = true
            )
        }
        val req = sign("HEAD", key)
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 200 -> VfsEntry(
                        path = RemoteEngine.locationPath(loc.id, "/$key"),
                        name = displayName("/$key"),
                        isDirectory = false,
                        size = resp.header("Content-Length")?.toLongOrNull() ?: 0,
                        lastModified = resp.header("Last-Modified")?.let {
                            try {
                                val fmt = java.text.SimpleDateFormat(
                                    "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
                                )
                                fmt.parse(it)?.time ?: 0
                            } catch (_: Exception) { 0 }
                        } ?: 0
                    )
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 增删改 ----------

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        val key = keyOf(path).trimEnd('/') + "/"
        val req = sign("PUT", key, body = ByteArray(0))
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw VfsException("S3 建目录失败(${resp.code})")
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        val key = keyOf(path)
        if (recursive) {
            // 递归删除：列出全部前缀对象逐个删
            val prefix = key.trimEnd('/') + "/"
            var token: String? = null
            do {
                val query = mutableMapOf(
                    "list-type" to "2",
                    "prefix" to prefix,
                    "max-keys" to "1000"
                )
                token?.let { query["continuation-token"] = it }
                val listReq = sign("GET", prefix, query)
                val (keys, next) = http.newCall(listReq).execute().use { resp ->
                    if (!resp.isSuccessful) throw VfsException("S3 列举失败(${resp.code})")
                    parseKeys(resp.body?.string().orEmpty())
                }
                for (k in keys) deleteKey(k)
                token = next
            } while (token != null && token!!.isNotEmpty())
            // 删除目录标记对象
            deleteKey(prefix)
        } else {
            deleteKey(key)
        }
    }

    private suspend fun deleteKey(key: String) = withContext(Dispatchers.IO) {
        val req = sign("DELETE", key)
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw VfsException("S3 删除失败(${resp.code})")
        }
    }

    private fun parseKeys(xml: String): Pair<List<String>, String?> {
        val keys = ArrayList<String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(java.io.StringReader(xml))
        var event = parser.eventType
        var inContents = false
        var key = ""
        var token: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Contents" -> { inContents = true; key = "" }
                    "Key" -> if (inContents) key = parser.nextText()
                    "NextContinuationToken" -> token = parser.nextText()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Contents" -> { inContents = false; keys.add(key) }
                }
            }
            event = parser.next()
        }
        return keys to token
    }

    override suspend fun rename(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        // S3 无 rename：文件走 CopyObject+DELETE；目录不支持重命名
        val oldKey = keyOf(oldPath).trimEnd('/')
        val newKey = keyOf(newPath).trimEnd('/')
        if (stat(oldPath)?.isDirectory == true) {
            throw VfsException("S3 不支持目录重命名")
        }
        val copyReq = sign("PUT", newKey, copySource = "${bucket}/${oldKey}")
        http.newCall(copyReq).execute().use { resp ->
            if (!resp.isSuccessful) throw VfsException("S3 复制失败(${resp.code})")
        }
        deleteKey(oldKey)
    }

    // ---------- 读写 ----------

    override fun read(path: String): InputStream {
        val req = sign("GET", keyOf(path))
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw VfsException("S3 读取失败(${resp.code})")
        }
        return resp.body?.byteStream() ?: throw VfsException("响应体为空")
    }

    override fun write(path: String): OutputStream {
        // 用 PipedStream 桥接：外部写 → OkHttp 读 → PUT
        val pipedOut = java.io.PipedOutputStream()
        val pipedIn = java.io.PipedInputStream(pipedOut, 1 shl 16)
        val streamBody = object : RequestBody() {
            override fun contentType(): okhttp3.MediaType? = null
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
            try {
                val req = sign("PUT", keyOf(path), bodyStream = streamBody)
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw VfsException("S3 上传失败(${resp.code})")
                }
            } catch (_: Exception) {
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()
        return pipedOut
    }

    override fun close() {}
}
